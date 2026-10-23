package mr.natijati.results.importing;

import mr.natijati.results.exam.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.item.ExecutionContext;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OfficialFormatTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"72.252200000000002,72.2522", "131.08000000000001,131.08",
        "75.349999999999994,75.35", "94.251999999999995,94.252", "75.540000000000006,75.54",
        "12.345,12.345", "4.263157895,4.263157895", "9.947368421,9.947368421",
        "999999.123456789123,999999.123456789123"})
    void c1asScoresOnlyRemoveNegligibleExcelNoise(String raw, String expected) {
        assertThat(c1as(raw).totalScore()).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings={"-1", "-0.000000000000001", "1.1234567891234", "72.2522000000001",
        "1000000", "999999.999999999999999", "1E1000", "1E-1000"})
    void incompatibleC1asScoresRemainRejected(String raw) {
        assertThatThrownBy(() -> c1as(raw)).isInstanceOf(InvalidExamRowException.class)
            .hasMessageContaining("TOTAL").hasMessageNotContaining(raw);
    }

    @ParameterizedTest
    @ValueSource(strings={"7.8", "4.263157895", "5.85", "7.9", "9.725", "5.842105263",
        "9.763157895", "8.5", "7.7875", "0", "4.6875", "9.7", "8.475", "8.75", "9.9",
        "6.025", "5.9", "5.625", "7.95", "10.1", "8.6", "9.947368421", "9.8875",
        "4.2631578947368398", "5.8421052631578902", "9.7631578947368407"})
    void bepcPreservesProvidedExamplesAndActualXmlPrecision(String score) {
        assertThat(bepc(score, "Admis").score()).isEqualByComparingTo(score);
    }

    @ParameterizedTest
    @CsvSource({"Admis,ADMITTED", "Ajourné,FAILED", "Absent,ABSENT",
        "Examen annulé -Telephone,CANCELLED", "Examen-Annulé-Telephone,CANCELLED", "Eliminé,ELIMINATED"})
    void allOfficialDecisionsPreserveTheirOriginalLabel(String label, DecisionCode code) {
        var command = bepc("0", label);
        assertThat(command.decisionCode()).isEqualTo(code);
        assertThat(command.decisionOriginal()).isEqualTo(label);
    }

    @Test void unknownDecisionsAndInvalidBepcScoresRemainRejected() {
        assertThatThrownBy(() -> bepc("1", "Examen annulé autre motif")).hasMessageContaining("DECISION");
        for (String score : new String[]{"-1", "21", "0.000000000000000000123"}) {
            assertThatThrownBy(() -> bepc(score, "Admis")).hasMessageContaining("MOYG");
        }
    }

    @Test void sourceIdentifiersRemainTextWhileNumericKeysRemainExact() {
        var c = c1as("12.345");
        assertThat(c.dossierNumberOriginal()).isEqualTo("0000000123");
        assertThat(c.candidateNumberOriginal()).isEqualTo("007");
        assertThat(c.dossierNumber()).isEqualTo(123);
        assertThat(c.candidateNumber()).isEqualTo(7);
        var b = bepc("9.947368421", "Admis");
        assertThat(b.candidateNumberOriginal()).isEqualTo("00296");
        assertThat(b.candidateNumber()).isEqualTo(296);
        assertThat(b.wilayaCode()).isEqualTo(8);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ExamType.class, names={"C1AS","BEPC"})
    void realHeadersAndIgnoredColumnsAreReadWithoutVisualRounding(ExamType exam) throws Exception {
        Path path = directory.resolve("official-format.xlsx");
        String[] headers = exam == ExamType.C1AS
            ? new String[]{"Noreg","Numéro_C1AS","NODOSS","NOM_AR","LIEU NAISS_AR","TYPE","Ecole_AR","ANNEE_NAISS","WILAYA_AR","MOUGHATAA_AR","Centre Examen_AR","TOTAL"}
            : new String[]{"Numéro","NOM","DATE NISS","LIEU NAISS","Code Wilaya","Wilaya","Centre","Moyg","Decision"};
        String[] values = exam == ExamType.C1AS
            ? new String[]{"01","007","0000000123","Synthetic","ignored","OF","School","2013","Wilaya","Moughataa","Centre","72.252200000000002"}
            : new String[]{"00296","Synthetic","ignored","ignored","08","Wilaya","Centre","4.2631578947368398","Examen annulé -Telephone"};
        int scoreColumn = exam == ExamType.C1AS ? 11 : 7;
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet(); var head = sheet.createRow(0); var row = sheet.createRow(1);
            for (int i=0; i<headers.length; i++) { head.createCell(i).setCellValue(headers[i]); row.createCell(i).setCellValue(values[i]); }
            var score = row.getCell(scoreColumn);
            score.setCellValue(Double.parseDouble(values[scoreColumn]));
            score.getCTCell().setV(values[scoreColumn]); // actual XML spelling, no double conversion
            var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("0.0")); score.setCellStyle(style);
            try (var output = Files.newOutputStream(path)) { book.write(output); }
        }
        var report = new ImportPreflight(10).inspect(path, exam);
        assertThat(report.rows()).isEqualTo(1);
        assertThat(report.ignoredColumns()).isEqualTo(exam == ExamType.C1AS ? 3 : 2);
        var reader = new ExcelResultItemReader(path, exam, 10);
        try {
            reader.open(new ExecutionContext()); var row = reader.read();
            for (int i=0; i<headers.length; i++) assertThat(row.values().get(ExcelHeaderNormalizer.normalize(headers[i]))).isEqualTo(values[i]);
            var command = new ExamResultProcessor(exam).process(row);
            if (command instanceof C1asResultCommand c) assertThat(c.totalScore()).isEqualByComparingTo("72.2522");
            if (command instanceof BepcResultCommand b) assertThat(b.score()).isEqualByComparingTo("4.2631578947368398");
        } finally { reader.close(); }
        try (var files = Files.list(directory)) { assertThat(files.toList()).containsExactly(path); }
    }

    @Test void c1as121140RowsPassConfiguredLimitAndFailBelowIt() throws Exception {
        Path path = directory.resolve("volume.xlsx");
        try (var book = new SXSSFWorkbook(100)) {
            var sheet = book.createSheet(); var header = sheet.createRow(0);
            header.createCell(0).setCellValue("NODOSS"); header.createCell(1).setCellValue("NOM_AR"); header.createCell(2).setCellValue("TOTAL");
            for (int i=1; i<=121140; i++) {
                var row = sheet.createRow(i); row.createCell(0).setCellValue(i);
                row.createCell(1).setCellValue("Synthetic"); row.createCell(2).setCellValue(12.345);
            }
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        assertThat(new ImportPreflight(150000).validate(path, ExamType.C1AS)).isEqualTo(121140);
        assertThatThrownBy(() -> new ImportPreflight(121139).validate(path, ExamType.C1AS)).hasMessageContaining("Limite");
        try (var files = Files.list(directory)) { assertThat(files.toList()).containsExactly(path); }
    }

    private C1asResultCommand c1as(String score) {
        return (C1asResultCommand)new ExamResultProcessor(ExamType.C1AS).process(new RawExamRow(55082,
            Map.of("NODOSS","0000000123","NUMERO_C1AS","007","NOM_AR","Synthetic","TOTAL",score)));
    }
    private BepcResultCommand bepc(String score, String decision) {
        return (BepcResultCommand)new ExamResultProcessor(ExamType.BEPC).process(new RawExamRow(3,
            Map.of("NUMERO","00296","NOM","Synthetic","CODE_WILAYA","08","MOYG",score,"DECISION",decision)));
    }
}
