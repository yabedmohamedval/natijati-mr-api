package mr.natijati.results.integration;

import mr.natijati.results.exam.*;
import mr.natijati.results.importing.async.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AsyncImportIT extends ImportIntegrationSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ImportObjectStorage objects;
    @Autowired ImportWorkerLock lock;
    @Autowired ImportWorkerTransactions transitions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AsyncImportTestConfiguration.FakeImportJobDispatcher dispatcher;

    @BeforeEach void resetFake() { dispatcher.fail=false;dispatcher.dispatched.clear(); }
    ImportExecutionResponse submit(ExamType exam, byte[] content) throws Exception {
        var response=imports.importResults(new MockMultipartFile("file","../../private-name.xlsx","application/octet-stream",content),exam,2026,SessionType.NORMAL);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation().toString()).endsWith(response.getBody().importId().toString());
        return response.getBody();
    }
    @ParameterizedTest @EnumSource(ExamType.class)
    void submissionDoesNotValidateOrRunBatchAndWorkerBecomesReady(ExamType exam) throws Exception {
        var queued=submit(exam,excel(exam,1001,false,false));
        assertThat(queued.status()).isEqualTo(ImportStatus.QUEUED);
        assertThat(queued.expectedRows()).isNull();assertThat(queued.sessionId()).isNull();
        assertThat(dispatcher.dispatched).containsExactly(queued.importId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_session",Long.class)).isZero();
        assertThat(objects.exists(executions.get(queued.importId()).objectKey())).isTrue();
        assertThat(worker.run(queued.importId(),"synthetic-execution")).isZero();
        var done=imports.status(queued.importId());
        assertThat(done.status()).isEqualTo(ImportStatus.READY);assertThat(done.writtenRows()).isEqualTo(1001);
        assertThat(done.readRows()).isEqualTo(1001);assertThat(done.progressPercent()).isEqualByComparingTo("100.00");
        assertThat(done.startedAt()).isNotNull();assertThat(done.finishedAt()).isNotNull();
        assertThat(done.durationMs()).isNotNegative();
        assertThat(objects.exists(executions.get(done.importId()).objectKey())).isFalse();
        assertThat(worker.run(done.importId(),"duplicate-execution")).isZero();
        assertThat(imports.status(done.importId()).attemptCount()).isEqualTo(1);
        mvc.perform(post("/api/admin/imports/"+done.sessionId()+"/publish").with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        noTemporaryFiles();
    }
    @Test void invalidWorkbookFailsAsynchronouslyWithoutBusinessSession() throws Exception {
        for(byte[] file:List.of(excel(ExamType.BAC,2,true,false),excel(ExamType.BAC,2,false,true),excel(ExamType.BEPC,2,false,false),excel(ExamType.BAC,0,false,false))) {
            var accepted=submit(ExamType.BAC,file);
            assertThat(worker.run(accepted.importId(),"validation-worker")).isEqualTo(1);
            var failed=imports.status(accepted.importId());
            assertThat(failed.status()).isEqualTo(ImportStatus.FAILED);assertThat(failed.sessionId()).isNull();
            assertThat(failed.errors()).extracting(ImportExecutionResponse.Error::code).containsExactly("VALIDATION_FAILED");
            assertThat(objects.exists(executions.get(failed.importId()).objectKey())).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_session",Long.class)).isZero();
    }
    @Test void statusAndListRequireAdminAndContainOnlyMetadata() throws Exception {
        var queued=submit(ExamType.C1AS,excel(ExamType.C1AS,1,false,false));
        mvc.perform(get("/api/admin/imports/"+queued.importId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/imports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/imports").with(user("reader").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/imports/"+queued.importId()).with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.objectKey").doesNotExist()).andExpect(jsonPath("$.progressPercent").isEmpty());
        mvc.perform(get("/api/admin/imports").param("limit","1").with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/admin/imports").param("limit","101").with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/imports/"+UUID.randomUUID()).with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isNotFound());
    }
    @Test void duplicateSubmissionAndWorkerAreExcludedByDatabase() throws Exception {
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,1,false,false));
        assertThatThrownBy(()->submit(ExamType.BAC,excel(ExamType.BAC,1,false,false))).isInstanceOf(IllegalStateException.class);
        try(var held=lock.acquire()) {
            assertThatThrownBy(()->worker.run(queued.importId(),"duplicate")).isInstanceOf(IllegalStateException.class);
            assertThat(imports.status(queued.importId()).attemptCount()).isZero();
        }
        assertThat(worker.run(queued.importId(),"winner")).isZero();
    }
    @Test void ambiguousDispatchRetainsDurableSourceAndCanBeRecovered() throws Exception {
        dispatcher.fail=true;
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,1,false,false));
        assertThat(queued.status()).isEqualTo(ImportStatus.QUEUED);
        assertThat(queued.errors()).extracting(ImportExecutionResponse.Error::code).containsExactly("DISPATCH_UNCONFIRMED");
        assertThat(objects.exists(executions.get(queued.importId()).objectKey())).isTrue();
        assertThat(worker.run(queued.importId(),"redrive")).isZero();
        assertThat(imports.status(queued.importId()).errors()).isEmpty();
    }
    @Test void missingSourceFailsWithoutSessionAndRequestsNewUpload() throws Exception {
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,1,false,false));
        objects.delete(executions.get(queued.importId()).objectKey());
        assertThat(worker.run(queued.importId(),"missing-source")).isEqualTo(1);
        assertThat(imports.status(queued.importId()).status()).isEqualTo(ImportStatus.FAILED);
        assertThat(imports.status(queued.importId()).sessionId()).isNull();
    }
    @Test void staleAttemptCannotWriteAndProgressRollsBackWithChunk() throws Exception {
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,1,false,false));
        try(var held=lock.acquire()) {
            var first=executions.claim(queued.importId(),"first");
            transitions.recoverAndValidate(queued.importId(),first.attemptCount());
            assertThat(imports.status(queued.importId()).status()).isEqualTo(ImportStatus.VALIDATING);
            transitions.prepare(queued.importId(),first.attemptCount(),500,0);
            var tx=new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(s->{ executions.progress(queued.importId(),first.attemptCount(),500);s.setRollbackOnly(); });
            assertThat(imports.status(queued.importId()).writtenRows()).isZero();
            var second=executions.claim(queued.importId(),"second");
            assertThatThrownBy(()->tx.executeWithoutResult(s->executions.progress(queued.importId(),first.attemptCount(),500)))
                .isInstanceOf(IllegalStateException.class);
            assertThat(second.attemptCount()).isEqualTo(2);
        }
    }
    @Test void storageOutageDuringWorkerRetainsSourceAndAllowsRecovery() throws Exception {
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,2,false,false));
        org.mockito.Mockito.doThrow(new java.io.IOException("synthetic download outage"))
            .when(objects).download(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(()->worker.run(queued.importId(),"storage-down")).isInstanceOf(IllegalStateException.class);
        var interrupted=imports.status(queued.importId());
        assertThat(interrupted.status()).isEqualTo(ImportStatus.VALIDATING);
        assertThat(interrupted.sessionId()).isNull();
        assertThat(interrupted.errors()).extracting(ImportExecutionResponse.Error::code).containsExactly("RECOVERY_REQUIRED");
        assertThat(objects.exists(executions.get(queued.importId()).objectKey())).isTrue();
        org.mockito.Mockito.doCallRealMethod().when(objects).download(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        assertThat(worker.run(queued.importId(),"storage-restored")).isZero();
        assertThat(imports.status(queued.importId()).writtenRows()).isEqualTo(2);
    }
    @Test void terminalCleanupCanBeRetriedEvenWhileAnotherWorkerOwnsLock() throws Exception {
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,2,false,false));
        String key=executions.get(queued.importId()).objectKey();
        org.mockito.Mockito.doThrow(new java.io.IOException("synthetic deletion outage")).when(objects).delete(key);
        assertThat(worker.run(queued.importId(),"cleanup-down")).isZero();
        assertThat(executions.get(queued.importId()).sourceDeletedAt()).isNull();
        assertThat(objects.exists(key)).isTrue();
        org.mockito.Mockito.doCallRealMethod().when(objects).delete(key);
        try(var held=lock.acquire()) { assertThat(worker.run(queued.importId(),"cleanup-restored")).isZero(); }
        assertThat(objects.exists(key)).isFalse();
        assertThat(executions.get(queued.importId()).sourceDeletedAt()).isNotNull();
        assertThat(imports.status(queued.importId()).attemptCount()).isEqualTo(1);
    }
    @Test void twoConcurrentWorkersHaveExactlyOneClaim() throws Exception {
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,2,false,false));
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{
            entered.countDown();
            if(!release.await(10,TimeUnit.SECONDS))throw new java.io.IOException("test timeout");
            return invocation.callRealMethod();
        }).when(objects).download(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->worker.run(queued.importId(),"first-worker"));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var second=executor.submit(()->worker.run(queued.importId(),"second-worker"));
                assertThatThrownBy(()->second.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(imports.status(queued.importId()).attemptCount()).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS)).isZero();
        }
        assertThat(imports.status(queued.importId()).writtenRows()).isEqualTo(2);
    }
    @Test void killedWorkerAfter2000CommitsRestartsFromZeroWithoutDuplicates() throws Exception {
        var queued=submit(ExamType.BAC,excel(ExamType.BAC,5000,false,false));
        Path childTemp=Files.createTempDirectory("crashed-worker-staging-");
        Path childLog=Path.of("target/crashed-worker.log").toAbsolutePath();
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx384m","-cp",
            System.getProperty("java.class.path"),CrashImportWorkerProcess.class.getName(),
            "--spring.config.location=classpath:/application.yml,classpath:/application-integration.properties",
            "--spring.profiles.active=integration","--spring.datasource.url="+postgres.getJdbcUrl(),
            "--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
            "--spring.flyway.enabled=false",
            "--spring.batch.jdbc.initialize-schema=never","--app.import-directory="+childTemp,
            "--test.object-directory="+objectDirectory,"--test.import-id="+queued.importId()));
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(childLog.toFile());
        builder.environment().clear(); // no inherited DB/GCP/admin credentials
        var process=builder.start();
        try {
            assertThat(process.waitFor(60,TimeUnit.SECONDS)).as("child worker finished; see target/crashed-worker.log").isTrue();
            assertThat(process.exitValue()).as("abrupt halt after fourth commit").isEqualTo(137);
            var interrupted=imports.status(queued.importId());
            assertThat(interrupted.status()).isEqualTo(ImportStatus.IMPORTING);
            assertThat(interrupted.expectedRows()).isEqualTo(5000);assertThat(interrupted.writtenRows()).isEqualTo(2000);
            assertThat(interrupted.readRows()).isEqualTo(2000);assertThat(interrupted.progressPercent()).isEqualByComparingTo("40.00");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bac_result",Long.class)).isEqualTo(2000);
            assertThat(jdbc.queryForObject("SELECT status FROM batch_step_execution WHERE job_execution_id=?",String.class,interrupted.batchJobExecutionId())).isEqualTo("STARTED");
            assertThatThrownBy(()->sessions.publish(interrupted.sessionId())).isInstanceOf(IllegalStateException.class);
            assertThat(objects.exists(executions.get(queued.importId()).objectKey())).isTrue();
            long firstResultId=jdbc.queryForObject("SELECT min(id) FROM bac_result",Long.class);
            assertThat(worker.run(queued.importId(),"recovered-worker")).isZero();
            var ready=imports.status(queued.importId());
            assertThat(ready.status()).isEqualTo(ImportStatus.READY);assertThat(ready.attemptCount()).isEqualTo(2);
            assertThat(ready.writtenRows()).isEqualTo(5000);assertThat(ready.sessionId()).isEqualTo(interrupted.sessionId());
            assertThat(ready.batchJobExecutionId()).isNotEqualTo(interrupted.batchJobExecutionId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bac_result",Long.class)).isEqualTo(5000);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT candidate_number) FROM bac_result",Long.class)).isEqualTo(5000);
            assertThat(jdbc.queryForObject("SELECT min(id) FROM bac_result",Long.class)).isGreaterThan(firstResultId+1999);
            assertThat(jdbc.queryForObject("SELECT status FROM batch_step_execution WHERE job_execution_id=?",String.class,interrupted.batchJobExecutionId())).isEqualTo("ABANDONED");
            assertThat(worker.run(queued.importId(),"duplicate-after-ready")).isZero();
            assertThat(imports.status(queued.importId()).attemptCount()).isEqualTo(2);
        } finally {
            process.destroyForcibly();
            process.waitFor(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(childTemp)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }
    }
}
