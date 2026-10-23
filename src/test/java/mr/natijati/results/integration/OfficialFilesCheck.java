package mr.natijati.results.integration;

import mr.natijati.results.exam.*;
import mr.natijati.results.importing.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.item.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.*;
import java.math.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in only. Real files stay outside git; all writes use Testcontainers. */
class OfficialFilesCheck extends ImportIntegrationSupport {
    @Autowired ImportPreflight preflight;
    @Autowired ObjectMapper json;

    @ParameterizedTest
    @EnumSource(value=ExamType.class, names={"C1AS","BEPC"})
    void officialFileIsImportedAndEveryStoredRowMatchesSource(ExamType exam) throws Exception {
        String property = "import.official." + exam.name().toLowerCase();
        String configured = System.getProperty(property);
        assertThat(configured).as("Explicit local file property %s is required", property).isNotBlank();
        Path original = Path.of(configured).toAbsolutePath();
        byte[] hash = digest(original);
        Path copy = Files.createTempFile(directory, "official-copy-", ".xlsx");
        long expected = exam == ExamType.C1AS ? 121140 : 75694;
        int ignored = exam == ExamType.C1AS ? 3 : 2;
        try {
            Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
            long preflightStart = System.nanoTime();
            var checked = preflight.inspect(copy, exam);
            double preflightSeconds = (System.nanoTime() - preflightStart) / 1e9;
            assertThat(checked.rows()).isEqualTo(expected);
            assertThat(checked.ignoredColumns()).isEqualTo(ignored);
            var pools = ManagementFactory.getMemoryPoolMXBeans();
            pools.forEach(MemoryPoolMXBean::resetPeakUsage);
            var report = upload(exam, SessionType.NORMAL, Files.readAllBytes(copy));
            long peak = pools.stream().filter(p -> p.getType() == MemoryType.HEAP)
                .mapToLong(p -> p.getPeakUsage().getUsed()).sum();
            assertThat(report.batchStatus()).isEqualTo("COMPLETED");
            assertThat(report.sessionStatus()).isEqualTo("READY");
            assertThat(report.readRows()).isEqualTo(expected);
            assertThat(report.importedRows()).isEqualTo(expected);
            assertThat(report.rejectedRows()).isZero();
            assertThat(report.errors()).isEmpty();
            assertThat(report.ignoredColumns()).isEqualTo(ignored);
            var session = sessions.getRequired(report.sessionId());
            assertThat(session.getResultCount()).isEqualTo(expected);
            assertThat(session.getYear()).isEqualTo(2026);
            // Same preconditions as publish(), without publishing any official result.
            assertThat(session.getStatus()).isEqualTo(PublicationStatus.READY);
            String table = exam == ExamType.C1AS ? "c1as_result" : "bepc_result";
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE exam_session_id=?", Long.class, report.sessionId())).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_session WHERE published_at IS NOT NULL", Long.class)).isZero();
            String scoreColumn = exam == ExamType.C1AS ? "total_score" : "score";
            assertThat(jdbc.queryForObject("SELECT numeric_scale FROM information_schema.columns WHERE table_name=? AND column_name=?", Integer.class, table, scoreColumn))
                .isEqualTo(exam == ExamType.C1AS ? 12 : 18);
            var samples = compareEveryRow(copy, exam, table, report.sessionId(), expected);
            var decisions = exam == ExamType.BEPC ? jdbc.queryForList("SELECT decision_code,count(*) AS rows FROM bepc_result GROUP BY decision_code ORDER BY decision_code") : List.of();
            if (exam == ExamType.BEPC) {
                assertThat(jdbc.queryForObject("SELECT count(DISTINCT wilaya_code) FROM bepc_result", Integer.class)).isEqualTo(16);
                assertThat(jdbc.queryForObject("SELECT count(DISTINCT exam_center_fr) FROM bepc_result", Integer.class)).isEqualTo(354);
                for (var entry : Map.of("ADMITTED",20890L,"FAILED",51229L,"ABSENT",2942L,"CANCELLED",162L,"ELIMINATED",471L).entrySet())
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM bepc_result WHERE decision_code=?",Long.class,entry.getKey())).isEqualTo(entry.getValue());
            }
            double batchSeconds = jdbc.queryForObject("SELECT extract(epoch FROM (end_time-start_time)) FROM batch_step_execution WHERE job_execution_id=?", Double.class, report.jobExecutionId());
            Map<String,Object> metrics = new LinkedHashMap<>();
            metrics.put("exam", exam.name()); metrics.put("rows", checked.rows()); metrics.put("readRows", report.readRows());
            metrics.put("resultCount", session.getResultCount()); metrics.put("rejectedRows", report.rejectedRows());
            metrics.put("status", report.batchStatus()); metrics.put("sessionStatus", report.sessionStatus());
            metrics.put("preflightSeconds", preflightSeconds); metrics.put("batchSeconds", batchSeconds);
            metrics.put("uploadSecondsIncludingPreflight", report.durationMs()/1000.0);
            metrics.put("batchRowsPerSecond", expected/batchSeconds); metrics.put("peakHeapPoolsMiB",peak/1048576.0);
            metrics.put("verifiedRows", expected); metrics.put("scoreSamples", samples); metrics.put("decisions", decisions);
            Path output = Path.of("target/official-audit"); Files.createDirectories(output);
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve(exam.name().toLowerCase()+"-import.json").toFile(),metrics);
            System.out.println("OFFICIAL_IMPORT " + json.writeValueAsString(metrics));
        } finally {
            Files.deleteIfExists(copy);
            assertThat(Arrays.equals(hash, digest(original))).as("Original file unchanged").isTrue();
        }
        noTemporaryFiles();
    }

    private List<Map<String,Object>> compareEveryRow(Path source, ExamType exam, String table, long session, long expected) throws Exception {
        var reader = new ExcelResultItemReader(source, exam, 150000);
        Map<String,String> textColumns = exam == ExamType.C1AS
            ? Map.of("candidate_number_original","NUMERO_C1AS","dossier_number_original","NODOSS","name_ar","NOM_AR",
                "candidate_type","TYPE","school_ar","ECOLE_AR","wilaya_ar","WILAYA_AR","moughataa_ar","MOUGHATAA_AR","exam_center_ar","CENTRE_EXAMEN_AR")
            : Map.of("candidate_number_original","NUMERO","name_fr","NOM","wilaya_fr","WILAYA","exam_center_fr","CENTRE","decision_original","DECISION");
        Set<String> wanted = new HashSet<>(List.of("4.263157895","5.842105263","9.763157895","9.947368421","10.1","0"));
        List<Map<String,Object>> samples = new ArrayList<>();
        long count = 0;
        try (var connection = datasource.getConnection()) {
            connection.setAutoCommit(false); connection.setReadOnly(true);
            try (var statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE exam_session_id=? ORDER BY id")) {
                statement.setLong(1, session); statement.setFetchSize(500);
                reader.open(new ExecutionContext());
                try (var rows = statement.executeQuery()) {
                    RawExamRow raw;
                    while ((raw = reader.read()) != null) {
                        assertThat(rows.next()).as("Database row present at Excel row %d",raw.rowNumber()).isTrue();
                        var values = raw.values();
                        for (var entry : textColumns.entrySet())
                            assertThat(Objects.equals(rows.getString(entry.getKey()),values.get(entry.getValue())))
                                .as("Text preserved at Excel row %d, field %s",raw.rowNumber(),entry.getValue()).isTrue();
                        String identifier = exam == ExamType.C1AS ? "NODOSS" : "NUMERO";
                        assertThat(rows.getLong(exam == ExamType.C1AS ? "dossier_number" : "candidate_number") == Long.parseLong(values.get(identifier)))
                            .as("Numeric identifier preserved at Excel row %d",raw.rowNumber()).isTrue();
                        if (exam == ExamType.C1AS)
                            assertThat(rows.getLong("candidate_number") == Long.parseLong(values.get("NUMERO_C1AS"))).as("C1AS number at row %d",raw.rowNumber()).isTrue();
                        else {
                            assertThat(rows.getInt("wilaya_code") == Integer.parseInt(values.get("CODE_WILAYA"))).as("Wilaya code at row %d",raw.rowNumber()).isTrue();
                            assertThat(rows.getString("decision_code").equals(DecisionNormalizer.normalize(values.get("DECISION")).name())).as("Decision at row %d",raw.rowNumber()).isTrue();
                        }
                        BigDecimal xml = new BigDecimal(values.get(exam == ExamType.C1AS ? "TOTAL" : "MOYG"));
                        BigDecimal reference = exam == ExamType.C1AS ? xml.setScale(12,RoundingMode.HALF_EVEN) : xml;
                        BigDecimal stored = rows.getBigDecimal(exam == ExamType.C1AS ? "total_score" : "score");
                        assertThat(stored).as("Score at Excel row %d",raw.rowNumber()).isEqualByComparingTo(reference);
                        // JSON numbers retain the decimal token; parse with BigDecimal, never double.
                        assertThat(new BigDecimal(json.writeValueAsString(stored))).isEqualByComparingTo(reference);
                        String roundedExample = xml.setScale(9,RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
                        if ((exam == ExamType.BEPC && wanted.remove(roundedExample)) || (exam == ExamType.C1AS && raw.rowNumber()==55082))
                            samples.add(Map.of("excelRow",raw.rowNumber(),"xml",xml.toPlainString(),"postgres",stored.toPlainString()));
                        count++;
                    }
                    assertThat(rows.next()).isFalse();
                }
            } finally { connection.rollback(); reader.close(); }
        }
        assertThat(count).isEqualTo(expected);
        if (exam == ExamType.BEPC) assertThat(wanted).isEmpty();
        return samples;
    }

    private byte[] digest(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
