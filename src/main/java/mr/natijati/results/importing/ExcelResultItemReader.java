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

    private Workbook workbook;
    private FormulaEvaluator formulaEvaluator;
    private Sheet sheet;
    private Map<Integer, String> headers;
    private int currentRowIndex;

    public ExcelResultItemReader(Path filePath, ExamType examType) {
        this.filePath = filePath;
        this.examType = examType;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        try {
            workbook = WorkbookFactory.create(filePath.toFile(), null, true);
            formulaEvaluator = workbook.getCreationHelper().createFormulaEvaluator();
            sheet = workbook.getSheetAt(0);

            Row headerRow = findFirstNonEmptyRow();
            if (headerRow == null) {
                throw new IllegalArgumentException(
                    "Le fichier Excel ne contient aucune ligne"
                );
            }

            headers = readHeaders(headerRow);
            validateRequiredHeaders(headers.values());

            currentRowIndex = executionContext.containsKey(CURRENT_ROW_KEY)
                ? executionContext.getInt(CURRENT_ROW_KEY)
                : headerRow.getRowNum() + 1;
        } catch (Exception exception) {
            throw new ItemStreamException(
                "Impossible d'ouvrir le fichier Excel : " + filePath,
                exception
            );
        }
    }

    @Override
    public RawExamRow read() {
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
        if (workbook == null) {
            return;
        }

        try {
            workbook.close();
        } catch (Exception exception) {
            throw new ItemStreamException(
                "Impossible de fermer le fichier Excel : " + filePath,
                exception
            );
        }
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
                result.put(cell.getColumnIndex(), normalized);
            }
        }

        return result;
    }

    private void validateRequiredHeaders(Collection<String> actualHeaders) {
        Set<String> required = switch (examType) {
            case BAC -> Set.of("NUM_BAC", "NOM_FR", "MOY_BAC", "DECISION");
            case BEPC -> Set.of("NUMERO", "NOM", "MOYG", "DECISION");
            case C1AS -> Set.of("NUMERO_C1AS", "NODOSS", "NOM_AR", "TOTAL");
        };

        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actualHeaders);

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "Colonnes obligatoires absentes pour " + examType + " : " + missing
            );
        }
    }

    private String formatCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell, formulaEvaluator).trim();
    }

    private boolean isEmpty(Row row) {
        for (Cell cell : row) {
            if (!formatCell(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
