package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamType;
import org.apache.poi.ss.usermodel.*;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;

import java.nio.file.Path;
import java.util.*;

public class ExcelResultItemReader implements ItemStreamReader<RawExamRow> {

    private static final String CURRENT_ROW_KEY = "excel.current.row";

    private final Path filePath;
    private final ExamType examType;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    private java.io.DataInputStream rows;
    private Path spool;
    private final int maxRows;
    private int spoolRowNumber;
    private Workbook workbook;
    private Sheet sheet;
    private Map<Integer, String> headers;
    private int currentRowIndex;

    public ExcelResultItemReader(Path filePath, ExamType examType) {
        this(filePath, examType, 100000);
    }

    public ExcelResultItemReader(Path filePath, ExamType examType, int maxRows) {
        this.filePath = filePath;
        this.examType = examType;
        this.maxRows = maxRows;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        try {
            if (org.apache.poi.poifs.filesystem.FileMagic.valueOf(filePath.toFile()) == org.apache.poi.poifs.filesystem.FileMagic.OOXML) {
                spool = XlsxRowSpool.create(filePath, examType, maxRows);
                rows = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(spool)));
                Map<Integer,String> first = readSpoolRow();
                if (first == null) throw invalid(0, "HEADERS", "Feuille vide");
                headers = new LinkedHashMap<>();
                for (var cell : first.entrySet()) {
                    String header=ExcelHeaderNormalizer.normalize(cell.getValue());
                    if (header.isBlank()) continue;
                    if (headers.containsValue(header)) throw invalid(spoolRowNumber,"HEADERS","Colonnes dupliquées après normalisation");
                    headers.put(cell.getKey(),header);
                }
                validateRequiredHeaders(headers.values());
                return;
            }
            workbook = WorkbookFactory.create(filePath.toFile(), null, true);
            if (workbook.getNumberOfSheets() != 1)
                throw invalid(0, "WORKBOOK", "Une seule feuille est requise");
            sheet = workbook.getSheetAt(0);

            Row headerRow = findFirstNonEmptyRow();
            if (headerRow == null) {
                throw new IllegalArgumentException(
                    "Le fichier Excel ne contient aucune ligne"
                );
            }

            headers = readHeaders(headerRow);
            validateRequiredHeaders(headers.values());

            currentRowIndex = headerRow.getRowNum() + 1;
        } catch (Exception exception) {
            close();
            if (exception instanceof InvalidExamRowException invalid) throw invalid;
            throw invalid(0, "WORKBOOK", "Classeur absent, vide, chiffré ou illisible");
        }
    }

    @Override
    public RawExamRow read() {
        if (rows != null) {
            Map<Integer,String> cells = readSpoolRow();
            if(cells == null)return null;
            Map<String,String> values=new LinkedHashMap<>();
            headers.forEach((column,header)->values.put(header,cells.getOrDefault(column,"")));
            return new RawExamRow(spoolRowNumber,values);
        }
        while (currentRowIndex <= sheet.getLastRowNum()) {
            Row row = sheet.getRow(currentRowIndex++);
            if (row == null || isEmpty(row)) {
                continue;
            }

            Map<String, String> values = new LinkedHashMap<>();
            headers.forEach((columnIndex, header) -> {
                Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                values.put(header, formatCell(cell));
            });

            return new RawExamRow(row.getRowNum() + 1, values);
        }

        return null;
    }

    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putInt(CURRENT_ROW_KEY, currentRowIndex);
    }

    @Override
    public void close() {
        if (rows != null || spool != null) {
            try { if(rows != null)rows.close(); }
            catch(java.io.IOException ex){throw new ItemStreamException("Fermeture lecture temporaire impossible");}
            finally {
                rows=null;
                try { if(spool != null)java.nio.file.Files.deleteIfExists(spool); }
                catch(java.io.IOException ex){throw new ItemStreamException("Nettoyage lecture temporaire impossible");}
                finally {spool=null;}
            }
        }
        if (workbook == null) {
            return;
        }

        try {
            workbook.close();
            workbook = null;
        } catch (Exception exception) {
            throw new ItemStreamException(
                "Impossible de fermer le fichier Excel : " + filePath,
                exception
            );
        }
    }

    private Map<Integer,String> readSpoolRow() {
        try {
            try { spoolRowNumber=rows.readInt(); } catch(java.io.EOFException end){return null;}
            int count=rows.readInt();
            Map<Integer,String> cells=new LinkedHashMap<>();
            for(int i=0;i<count;i++)cells.put(rows.readInt(),rows.readUTF());
            return cells;
        }catch(java.io.IOException ex){throw invalid(0,"WORKBOOK","Lecture temporaire incomplète");}
    }

    private Row findFirstNonEmptyRow() {
        for (int index = sheet.getFirstRowNum(); index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row != null && !isEmpty(row)) {
                return row;
            }
        }
        return null;
    }

    private Map<Integer, String> readHeaders(Row headerRow) {
        Map<Integer, String> result = new LinkedHashMap<>();

        for (Cell cell : headerRow) {
            String normalized = ExcelHeaderNormalizer.normalize(formatCell(cell));
            if (!normalized.isBlank()) {
                if (result.containsValue(normalized)) throw invalid(headerRow.getRowNum()+1, "HEADERS", "Colonnes dupliquées après normalisation");
                result.put(cell.getColumnIndex(), normalized);
            }
        }

        if (result.size() > 64) throw invalid(headerRow.getRowNum()+1, "HEADERS", "Trop de colonnes");
        return result;
    }

    public int ignoredColumnCount() {
        Set<String> used = switch (examType) {
            case BAC -> Set.of("NUM_BAC", "NOM_FR", "NOM_AR", "WILAYA_FR", "WILAYA_AR", "CENTRE_EXAMEN_FR", "CENTRE_EXAMEN_AR", "ETABLISSEMENT_FR", "ETABLISSEMENT_AR", "SERIE", "SERIE_FR", "SERIE_AR", "MOY_BAC", "DECISION");
            case BEPC -> Set.of("NUMERO", "NOM", "CODE_WILAYA", "WILAYA", "CENTRE", "MOYG", "DECISION");
            case C1AS -> Set.of("NUMERO_C1AS", "NODOSS", "NOM_AR", "TYPE", "ECOLE_AR", "WILAYA_AR", "MOUGHATAA_AR", "CENTRE_EXAMEN_AR", "TOTAL");
        };
        return (int) headers.values().stream().filter(h -> !used.contains(h)).count();
    }

    private void validateRequiredHeaders(Collection<String> actualHeaders) {
        Set<String> required = switch (examType) {
            case BAC -> Set.of("NUM_BAC", "NOM_FR", "MOY_BAC", "DECISION");
            case BEPC -> Set.of("NUMERO", "NOM", "MOYG", "DECISION");
            case C1AS -> Set.of("NODOSS", "NOM_AR", "TOTAL");
        };

        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actualHeaders);

        if (!missing.isEmpty()) {
            throw invalid(0, "HEADERS", "Colonnes obligatoires absentes : " + missing);
        }
    }

    private String formatCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
            throw invalid(cell.getRowIndex()+1, "CELL_" + (cell.getColumnIndex()+1), "Formule ou cellule en erreur interdite");
        if (cell.getCellType() == CellType.NUMERIC)
            return org.apache.poi.ss.util.NumberToTextConverter.toText(cell.getNumericCellValue());
        return formatter.formatCellValue(cell).trim();
    }

    private boolean isEmpty(Row row) {
        if (row.getLastCellNum() > 64) throw invalid(row.getRowNum()+1, "COLUMNS", "Trop de colonnes");
        boolean empty = true;
        for (Cell cell : row) {
            if (!formatCell(cell).isBlank()) empty = false;
        }
        return empty;
    }
    private InvalidExamRowException invalid(int row, String field, String reason) {
        return new InvalidExamRowException(examType, row, field, reason);
    }

}
