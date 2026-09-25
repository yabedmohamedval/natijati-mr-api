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
        mvc.perform(multipart("/api/admin/imports").file(new MockMultipartFile("file","test.xlsx","application/octet-stream",excel(ExamType.BAC,1,false,true)))
            .param("examType","BAC").param("year","2026").with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors[0].rowNumber").value(2)).andExpect(jsonPath("$.errors[0].field").value("MOY_BAC"))
            .andExpect(jsonPath("$.errors[0].value").value("[masquée]"));
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
