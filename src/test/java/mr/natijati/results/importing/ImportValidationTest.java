package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ImportValidationTest {
    @TempDir Path directory;
    private Path workbook(boolean legacy, java.util.function.Consumer<Workbook> edit) throws Exception {
        Path path=directory.resolve(legacy?"test.xls":"test.xlsx");
        try(Workbook book=legacy?new HSSFWorkbook():new XSSFWorkbook()) {
            var sheet=book.createSheet();var header=sheet.createRow(0);
            String[] headers={"Num_Bac","Nom_FR","Moy_Bac","Decision"};
            for(int i=0;i<4;i++)header.createCell(i).setCellValue(headers[i]);
            var row=sheet.createRow(1);row.createCell(0).setCellValue(10001);row.createCell(1).setCellValue("Synthetic");row.createCell(2).setCellValue(12.345);row.createCell(3).setCellValue("Admis");
            edit.accept(book);try(var out=Files.newOutputStream(path)){book.write(out);}
        }
        return path;
    }
    @Test void xlsAndNumericCellPrecisionRemainSupported() throws Exception {
        Path file=workbook(true,b->b.getSheetAt(0).getRow(1).getCell(2).setCellStyle(style(b,"0.0")));
        assertThat(new ImportPreflight(100).validate(file,ExamType.BAC)).isEqualTo(1);
        var reader=new ExcelResultItemReader(file,ExamType.BAC);
        try { reader.open(new org.springframework.batch.item.ExecutionContext());assertThat(reader.read().values().get("MOY_BAC")).isEqualTo("12.345"); } finally{reader.close();}
    }
    private CellStyle style(Workbook b,String format) {var s=b.createCellStyle();s.setDataFormat(b.createDataFormat().getFormat(format));return s;}
    @Test void missingAndDuplicateHeadersAreRejected() throws Exception {
        Path missing=workbook(false,b->b.getSheetAt(0).getRow(0).getCell(2).setCellValue("other"));
        assertThatThrownBy(()->new ImportPreflight(100).validate(missing,ExamType.BAC)).isInstanceOf(InvalidExamRowException.class).hasMessageContaining("HEADERS");
        Path duplicate=workbook(false,b->b.getSheetAt(0).getRow(0).createCell(4).setCellValue(" Num Bac "));
        assertThatThrownBy(()->new ImportPreflight(100).validate(duplicate,ExamType.BAC)).hasMessageContaining("dupliquées");
    }
    @Test void missingEmptyAndMultipleSheetsAreRejected() throws Exception {
        for(int mode=0;mode<3;mode++) {
            final int m=mode;
            Path file=workbook(false,b->{if(m==0)b.removeSheetAt(0);if(m==1)b.getSheetAt(0).removeRow(b.getSheetAt(0).getRow(1));if(m==2)b.createSheet();});
            assertThatThrownBy(()->new ImportPreflight(100).validate(file,ExamType.BAC)).isInstanceOf(InvalidExamRowException.class);
        }
    }
    @Test void formulasAreNotEvaluated() throws Exception {
        Path file=workbook(false,b->b.getSheetAt(0).getRow(1).getCell(2).setCellFormula("10+2"));
        assertThatThrownBy(()->new ImportPreflight(100).validate(file,ExamType.BAC)).hasMessageContaining("Formule");
    }
    @Test void blankRowsIgnoredAndUnknownColumnsDoNotShiftMappings() throws Exception {
        Path file=workbook(false,b->{b.getSheetAt(0).createRow(2);b.getSheetAt(0).getRow(0).createCell(8).setCellValue("Extra");b.getSheetAt(0).getRow(1).createCell(8).setCellValue("ignored");});
        assertThat(new ImportPreflight(100).inspect(file,ExamType.BAC).ignoredColumns()).isEqualTo(1);
        assertThatThrownBy(()->new ImportPreflight(0).validate(file,ExamType.BAC)).hasMessageContaining("Limite");
    }
    @ParameterizedTest @ValueSource(strings={"", "-1", "21", "not-a-score", "1.1234567891234", "1E2147483647"})
    void scoresMustBeValidWithoutSilentRounding(String score) {
        assertThatThrownBy(()->processor("10001",score,"Admis")).isInstanceOf(InvalidExamRowException.class).hasMessageContaining("MOY_BAC");
    }
    @ParameterizedTest @ValueSource(strings={"", "0", "-1", "1.2", "abc", "9223372036854775808"})
    void identifiersMustBePositiveExactIntegers(String identifier) {
        assertThatThrownBy(()->processor(identifier,"12","Admis")).isInstanceOf(InvalidExamRowException.class).hasMessageContaining("NUM_BAC");
    }
    @Test void unknownDecisionRejectedButAbsentMayHaveNoScore() {
        assertThatThrownBy(()->processor("1","12","not absent")).isInstanceOf(InvalidExamRowException.class).hasMessageContaining("DECISION");
        assertThat(((BacResultCommand)processor("1","","Absent")).score()).isNull();
        assertThat(((BacResultCommand)processor("1","12,5","Admis")).score()).isEqualByComparingTo("12.5");
    }
    private ExamResultCommand processor(String id,String score,String decision) {
        return new ExamResultProcessor(ExamType.BAC).process(new RawExamRow(7,Map.of("NUM_BAC",id,"NOM_FR","Synthetic","MOY_BAC",score,"DECISION",decision)));
    }
    @Test void headersHandleAccentsWhitespaceAndInvisibleCharacters() {
        assertThat(ExcelHeaderNormalizer.normalize(" \ufeffNúméro\u200b C1AS\u00a0 ")).isEqualTo("NUMERO_C1AS");
        assertThat(ExcelHeaderNormalizer.normalize(" Centre   Examen_AR ")).isEqualTo("CENTRE_EXAMEN_AR");
    }
    @Test void invalidExtensionAndMagicBytesLeaveNoFiles() throws Exception {
        var storage=new ImportStorageService(directory.toString());
        assertThatThrownBy(()->storage.store(new MockMultipartFile("file","x.csv","text/plain",new byte[]{1}))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->storage.store(new MockMultipartFile("file","x.xlsx","application/octet-stream",new byte[20]))).isInstanceOf(IllegalArgumentException.class);
        try(var paths=Files.list(directory)){assertThat(paths.toList()).isEmpty();}
    }
    @Test void uploadedFilenameNeverControlsStoragePath() throws Exception {
        Path source=workbook(false,b->{});
        var storage=new ImportStorageService(directory.resolve("uploads").toString());
        Path file=storage.store(new MockMultipartFile("file","../../private-name.xlsx","text/plain",Files.readAllBytes(source)));
        assertThat(file.getParent()).isEqualTo(directory.resolve("uploads"));assertThat(file.getFileName().toString()).startsWith("import-").doesNotContain("private-name");
        storage.delete(file);assertThat(file).doesNotExist();
    }
    @Test void formulaInAnUnlabelledColumnIsStillRejected() throws Exception {
        Path file=workbook(false,b->b.getSheetAt(0).getRow(1).createCell(6).setCellFormula("1+1"));
        assertThatThrownBy(()->new ImportPreflight(100).validate(file,ExamType.BAC)).hasMessageContaining("Formule");
    }

    @Test void declaredOversizeFileIsRejectedWithoutReadingIt() {
        var storage=new ImportStorageService(directory.toString());
        var file=new MockMultipartFile("file","big.xlsx","application/octet-stream",new byte[]{1}) {
            @Override public long getSize(){return 51L*1024*1024;}
        };
        assertThatThrownBy(()->storage.store(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50 MiB");
    }

}
