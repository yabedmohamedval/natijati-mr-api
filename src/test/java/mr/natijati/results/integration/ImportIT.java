package mr.natijati.results.integration;

import mr.natijati.results.exam.*;
import mr.natijati.results.importing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImportIT extends ImportIntegrationSupport {
    @Autowired JobLauncher launcher;
    @Autowired Job examResultImportJob;

    @Test void officialBepcPrecisionSurvivesJpaSearchJsonAndStatistics() throws Exception {
        String[] scores = {"4.2631578947368398", "5.8421052631578902", "9.7631578947368407", "9.9473684210526301", "10.1", "0"};
        String[] decisions = {"Ajourné", "Admis", "Absent", "Examen annulé -Telephone", "Examen-Annulé-Telephone", "Eliminé"};
        byte[] fixture;
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet(); var header = sheet.createRow(0);
            String[] headers = {"Numéro","NOM","DATE NISS","LIEU NAISS","Code Wilaya","Wilaya","Centre","Moyg","Decision"};
            for (int c=0;c<headers.length;c++) header.createCell(c).setCellValue(headers[c]);
            for (int i=0;i<scores.length;i++) {
                var row = sheet.createRow(i+1);
                String[] values = {String.format(java.util.Locale.ROOT,"%05d",296+i),"Synthetic","ignored","ignored","08","Synthetic wilaya","Synthetic centre",scores[i],decisions[i]};
                for (int c=0;c<values.length;c++) row.createCell(c).setCellValue(values[c]);
                row.getCell(7).setCellValue(Double.parseDouble(scores[i])); row.getCell(7).getCTCell().setV(scores[i]);
            }
            book.write(output); fixture = output.toByteArray();
        }
        var report = upload(ExamType.BEPC,SessionType.NORMAL,fixture);
        assertThat(report.batchStatus()).isEqualTo("COMPLETED");
        assertThat(report.ignoredColumns()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT candidate_number_original FROM bepc_result WHERE candidate_number=296",String.class)).isEqualTo("00296");
        sessions.publish(report.sessionId()); // synthetic data only
        var json = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        for (int i=0;i<scores.length;i++) {
            String id = String.format(java.util.Locale.ROOT,"%05d",296+i);
            var result = mvc.perform(get("/api/public/results/bepc/2026/"+id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionLabel").value(decisions[i])).andReturn().getResponse();
            assertThat(json.readTree(result.getContentAsString()).get("score").decimalValue()).isEqualByComparingTo(scores[i]);
            var search = mvc.perform(get("/api/public/catalog/search").param("examType","BEPC").param("year","2026").param("candidateNumber",id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andReturn().getResponse();
            assertThat(json.readTree(search.getContentAsString()).at("/content/0/score").decimalValue()).isEqualByComparingTo(scores[i]);
        }
        var statistics = mvc.perform(get("/api/public/catalog/statistics/BEPC/2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.candidateCount").value(6))
            .andExpect(jsonPath("$.admittedCount").value(1)).andExpect(jsonPath("$.cancelledCount").value(2))
            .andReturn().getResponse();
        assertThat(json.readTree(statistics.getContentAsString()).get("averageScore").decimalValue())
            .isEqualByComparingTo(jdbc.queryForObject("SELECT avg(score) FROM bepc_result",java.math.BigDecimal.class));
        noTemporaryFiles();
    }

    @ParameterizedTest @EnumSource(ExamType.class)
    void validExcelIsReadyAndPublishedOnlyExplicitly(ExamType exam) throws Exception {
        var report=upload(exam,SessionType.NORMAL,excel(exam,2,false,false));
        assertThat(report.batchStatus()).isEqualTo("COMPLETED"); assertThat(report.sessionStatus()).isEqualTo("READY");
        assertThat(report.importedRows()).isEqualTo(2);assertThat(report.readRows()).isEqualTo(2);assertThat(report.errors()).isEmpty();
        String url="/api/public/results/"+exam.name().toLowerCase()+"/2026/"+(exam==ExamType.C1AS?"dossier/":"")+"10001";
        mvc.perform(get(url)).andExpect(status().isNotFound());
        sessions.publish(report.sessionId());sessions.publish(report.sessionId());
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.score").value(12.5));
        noTemporaryFiles();
    }

    @ParameterizedTest @EnumSource(ExamType.class)
    void duplicateAndInvalidRowsFailBeforeSessionCreation(ExamType exam) throws Exception {
        assertThatThrownBy(()->upload(exam,SessionType.NORMAL,excel(exam,3,true,false))).isInstanceOf(InvalidExamRowException.class).hasMessageContaining("ligne 4");
        assertThatThrownBy(()->upload(exam,SessionType.NORMAL,excel(exam,3,false,true))).isInstanceOf(InvalidExamRowException.class).hasMessageContaining("ligne 4");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_session",Long.class)).isZero();noTemporaryFiles();
    }

    @Test void wrongExamAndEmptyFileAreRejectedAndCleaned() throws Exception {
        assertThatThrownBy(()->upload(ExamType.BEPC,SessionType.NORMAL,excel(ExamType.BAC,1,false,false))).isInstanceOf(InvalidExamRowException.class);
        assertThatThrownBy(()->upload(ExamType.C1AS,SessionType.NORMAL,excel(ExamType.BEPC,1,false,false))).isInstanceOf(InvalidExamRowException.class);
        assertThatThrownBy(()->upload(ExamType.BAC,SessionType.NORMAL,new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->upload(ExamType.BAC,SessionType.NORMAL,"not excel".getBytes())).isInstanceOf(IllegalArgumentException.class);
        noTemporaryFiles();
    }

    @Test void secondImportNeverOverwritesReadyOrPublished() throws Exception {
        var file=excel(ExamType.BAC,2,false,false);
        var first=upload(ExamType.BAC,SessionType.NORMAL,file);
        assertThatThrownBy(()->upload(ExamType.BAC,SessionType.NORMAL,file)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->upload(ExamType.BAC,SessionType.NORMAL,excel(ExamType.BAC,3,false,false))).isInstanceOf(IllegalStateException.class);
        sessions.publish(first.sessionId());
        assertThatThrownBy(()->upload(ExamType.BAC,SessionType.NORMAL,file)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bac_result",Long.class)).isEqualTo(2);noTemporaryFiles();
    }

    @Test void complementaryAndNormalAreIndependent() throws Exception {
        var normal=upload(ExamType.BAC,SessionType.NORMAL,excel(ExamType.BAC,2,false,false));
        var complementary=upload(ExamType.BAC,SessionType.COMPLEMENTARY,excel(ExamType.BAC,1,false,false));
        sessions.publish(normal.sessionId()); sessions.publish(complementary.sessionId());
        mvc.perform(get("/api/public/catalog/search").param("examType","BAC").param("year","2026").param("sessionType","NORMAL"))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/public/catalog/search").param("examType","BAC").param("year","2026").param("sessionType","COMPLEMENTARY"))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={500,2500,4500})
    void failureAfterCommittedChunkDeletesPartialRowsAndAllowsFreshAttempt(int committed) throws Exception {
        // Fail at 10%, 50% and 90% of 5,000 rows, after real chunk commits.
        jdbc.execute("CREATE FUNCTION reject_test_row() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.candidate_number="+(10000+committed+1)+" THEN RAISE EXCEPTION 'synthetic write failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_row BEFORE INSERT ON bac_result FOR EACH ROW EXECUTE FUNCTION reject_test_row()");
        ImportJobResponse failed;
        try { failed=upload(ExamType.BAC,SessionType.NORMAL,excel(ExamType.BAC,5000,false,false)); }
        finally { jdbc.execute("DROP TRIGGER reject_test_row ON bac_result"); jdbc.execute("DROP FUNCTION reject_test_row()"); }
        assertThat(failed.batchStatus()).isEqualTo("FAILED");assertThat(failed.sessionStatus()).isEqualTo("FAILED");
        assertThat(failed.importedRows()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM bac_result",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(write_count) FROM batch_step_execution WHERE job_execution_id=?",Long.class,failed.jobExecutionId())).isEqualTo(committed);
        assertThatThrownBy(()->sessions.publish(failed.sessionId())).isInstanceOf(IllegalStateException.class);
        noTemporaryFiles();
        var retry=upload(ExamType.BAC,SessionType.NORMAL,excel(ExamType.BAC,2,false,false));
        assertThat(retry.sessionId()).isEqualTo(failed.sessionId());assertThat(retry.jobExecutionId()).isNotEqualTo(failed.jobExecutionId());
        assertThat(retry.importedRows()).isEqualTo(2);assertThat(examResultImportJob.isRestartable()).isFalse();
    }

    @Test void databaseRejectsDuplicateEvenWhenPreflightIsBypassed() throws Exception {
        var session=sessions.prepareSession(ExamType.BAC,2026,SessionType.NORMAL,"synthetic");
        var file=Files.createTempFile(directory,"direct-",".xlsx");Files.write(file,excel(ExamType.BAC,2,true,false));
        try {
            var params=new JobParametersBuilder().addString("filePath",file.toString()).addString("examType","BAC").addLong("sessionId",session.getId()).toJobParameters();
            assertThat(launcher.run(examResultImportJob,params).getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bac_result",Long.class)).isZero();
            assertThatThrownBy(()->launcher.run(examResultImportJob,params)).isInstanceOf(org.springframework.batch.core.repository.JobRestartException.class);
        } finally { Files.deleteIfExists(file); }
    }

    @ParameterizedTest @EnumSource(value=PublicationStatus.class,names={"DRAFT","IMPORTING","FAILED","READY"})
    void emptyOrNonReadySessionCannotBePublished(PublicationStatus status) {
        long id=jdbc.queryForObject("INSERT INTO exam_session(exam_type,exam_year,session_type,status) VALUES('BAC',2026,'NORMAL',?) RETURNING id",Long.class,status.name());
        assertThatThrownBy(()->sessions.publish(id)).isInstanceOf(IllegalStateException.class);
    }

    @Test void simultaneousSameSessionReservationHasOneWinner() throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{start.await();try {sessions.prepareSession(ExamType.BAC,2026,SessionType.NORMAL,"synthetic");return true;}catch(IllegalStateException conflict){return false;}};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertThat(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_session",Long.class)).isEqualTo(1);
    }

    @Test void apiReturnsBoundedTechnicalDiagnosticWithoutPersonalValue() throws Exception {
        var result=mvc.perform(multipart("/api/admin/imports").file(new MockMultipartFile("file","test.xlsx","application/octet-stream",excel(ExamType.BAC,1,false,true)))
            .param("examType","BAC").param("year","2026").with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isAccepted()).andReturn();
        var id=java.util.UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.getResponse().getContentAsString()).get("importId").asText());
        worker.run(id,"synthetic-worker");
        mvc.perform(get("/api/admin/imports/"+id).with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.sessionId").isEmpty())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Synthetic candidate"))));
        noTemporaryFiles();
    }
    @Test void differentSessionReservationsCanProceedConcurrently() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var normal=executor.submit(()->sessions.prepareSession(ExamType.BAC,2026,SessionType.NORMAL,"synthetic"));
            var complementary=executor.submit(()->sessions.prepareSession(ExamType.BAC,2026,SessionType.COMPLEMENTARY,"synthetic"));
            assertThat(normal.get(10,TimeUnit.SECONDS).getId()).isNotEqualTo(complementary.get(10,TimeUnit.SECONDS).getId());
        }
    }

}
