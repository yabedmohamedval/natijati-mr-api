package mr.natijati.results.integration;

import mr.natijati.results.exam.*;
import mr.natijati.results.importing.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties={
    "spring.config.location=classpath:/application.yml,classpath:/application-integration.properties",
    "spring.profiles.active=integration", "spring.datasource.url=jdbc:disabled:integration-only",
    "spring.datasource.username=unused", "spring.datasource.password=unused",
    "spring.flyway.url=", "spring.flyway.user=", "spring.flyway.password=",
    "app.admin.username=integration-admin", "app.admin.password=synthetic-test-password"
})
@org.springframework.context.annotation.Import(AsyncImportTestConfiguration.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ImportIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-alpine");
    static final Path directory;
    static final Path objectDirectory;
    static { try { directory = Files.createTempDirectory("natijati-import-tests-"); objectDirectory=Files.createTempDirectory("natijati-objects-tests-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.import-directory", directory::toString); registry.add("test.object-directory",objectDirectory::toString); }
    @Autowired ImportController imports;
    @Autowired mr.natijati.results.importing.async.ImportWorker worker;
    @Autowired mr.natijati.results.importing.async.ImportExecutionRepository executions;
    @Autowired ImportPreflight preflight;
    @Autowired ImportStorageService staging;
    @Autowired ExamSessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired MockMvc mvc;

    @BeforeEach void cleanSyntheticDatabase() throws Exception {
        try (var connection=datasource.getConnection()) { assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl()); }
        jdbc.update("DELETE FROM import_execution");
        jdbc.update("DELETE FROM exam_session"); // isolated container, FK cascade, real commits
    }

    static byte[] excel(ExamType exam, int rows, boolean duplicate, boolean invalidScore) throws Exception {
        return excel(exam, rows, duplicate, invalidScore, false);
    }

    static byte[] excel(ExamType exam, int rows, boolean duplicate, boolean invalidScore, boolean wide) throws Exception {
        try (var workbook = new SXSSFWorkbook(100); var output = new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("Synthetic");
            String[] headers=switch(exam) {
                case BAC -> new String[]{"Num_Bac","Nom_FR","Moy_Bac","Decision"};
                case BEPC -> new String[]{"Numéro","NOM","Moyg","Decision"};
                case C1AS -> new String[]{"NODOSS","NOM_AR","TOTAL","Numéro_C1AS"};
            };
            if (wide && exam == ExamType.BAC) headers = new String[]{"Num_Bac","Nom_FR","Moy_Bac","Decision",
                "Noreg","Wilaya_FR","Wilaya_AR","Centre Examen FR","Centre Examen AR","Etablissement_FR",
                "Etablissement_AR","SERIE","Serie_FR","Serie_AR","NOM_AR","Lieun_FR","Lieun_AR","Date Naiss"};
            var head=sheet.createRow(0);
            for(int c=0;c<headers.length;c++)head.createCell(c).setCellValue(headers[c]);
            for(int i=1;i<=rows;i++) {
                var row=sheet.createRow(i);
                row.createCell(0).setCellValue(duplicate && i==rows ? 10001 : 10000+i);
                row.createCell(1).setCellValue("Synthetic candidate");
                if(invalidScore && i==rows)row.createCell(2).setCellValue("invalid"); else row.createCell(2).setCellValue(12.5);
                if(exam==ExamType.C1AS)row.createCell(3).setCellValue(90000+i);else row.createCell(3).setCellValue("Admis");
                for (int c=4;c<headers.length;c++) row.createCell(c).setCellValue("Synthetic " + c);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
    ImportJobResponse upload(ExamType exam, SessionType session, byte[] content) throws Exception {
        // Legacy engine assertions remain synchronous in this TEST harness only.
        // AsyncImportIT separately exercises submission without preflight and the real worker.
        var multipart=new MockMultipartFile("file","synthetic.xlsx","application/octet-stream",content);
        var local=staging.store(multipart);
        try { preflight.inspect(local,exam); } finally { staging.delete(local); }
        var accepted=imports.importResults(multipart,exam,2026,session).getBody();
        worker.run(accepted.importId(),"synthetic-worker");
        var e=executions.get(accepted.importId());
        var business=sessions.getRequired(e.sessionId());
        return new ImportJobResponse(e.sessionId(),e.batchJobExecutionId(),e.status()==mr.natijati.results.importing.async.ImportStatus.READY ? "COMPLETED" : "FAILED",
            business.getStatus().name(),business.getResultCount(),e.readRows(),e.rejectedRows(),e.durationMs(),
            e.errorCode()==null ? java.util.List.of() : java.util.List.of(new ImportIssue(exam.name(),0,"BATCH","[masquée]",e.errorSummary())),e.ignoredColumns());
    }
    void noTemporaryFiles() throws Exception { try(var files=Files.list(directory)) { assertThat(files.toList()).isEmpty(); } }
}
