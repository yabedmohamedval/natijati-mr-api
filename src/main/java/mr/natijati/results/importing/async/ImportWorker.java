package mr.natijati.results.importing.async;

import mr.natijati.results.importing.*;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.UUID;

@Service
public class ImportWorker {
    private final ImportExecutionRepository executions;
    private final ImportWorkerTransactions transactions;
    private final ImportObjectStorage storage;
    private final ImportWorkerLock lock;
    private final ImportPreflight preflight;
    private final JobLauncher launcher;
    private final Job job;
    private final Path directory;
    public ImportWorker(ImportExecutionRepository executions, ImportWorkerTransactions transactions,
        ImportObjectStorage storage, ImportWorkerLock lock, ImportPreflight preflight, JobLauncher launcher,
        Job examResultImportJob, @Value("${app.import-directory}") String directory) {
        this.executions=executions;this.transactions=transactions;this.storage=storage;this.lock=lock;
        this.preflight=preflight;this.launcher=launcher;this.job=examResultImportJob;this.directory=Path.of(directory);
    }
    public int run(UUID id, String executionName) throws Exception {
        var current=executions.get(id);
        if (current.status().terminal()) { cleanup(current); return current.status()==ImportStatus.READY ? 0 : 1; }
        try (var held=lock.acquire()) {
            var e=executions.claim(id,executionName);
            if (e.status().terminal()) { cleanup(e); return e.status()==ImportStatus.READY ? 0 : 1; }
            int attempt=e.attemptCount();
            Path local=null;
            try {
                transactions.recoverAndValidate(id,attempt);
                Files.createDirectories(directory);
                local=Files.createTempFile(directory,"worker-",e.objectKey().endsWith(".xlsx") ? ".xlsx" : ".xls");
                if (!storage.exists(e.objectKey())) {
                    transactions.failed(id,attempt,"SOURCE_MISSING");
                    var failed=executions.get(id);
                    cleanup(failed);
                    log(failed);
                    return 1;
                }
                storage.download(e.objectKey(),local);
                var report=preflight.inspect(local,e.examType());
                long sessionId=transactions.prepare(id,attempt,report.rows(),report.ignoredColumns());
                var parameters=new JobParametersBuilder().addString("filePath",local.toAbsolutePath().toString())
                    .addString("examType",e.examType().name()).addLong("sessionId",sessionId).addLong("expectedRows",report.rows())
                    .addString("importId",id.toString()).addLong("importAttempt",(long)attempt)
                    .addString("attempt",UUID.randomUUID().toString()).toJobParameters();
                var batch=launcher.run(job,parameters);
                if (batch.getStatus()!=BatchStatus.COMPLETED && !executions.get(id).status().terminal())
                    transactions.failed(id,attempt,"BATCH_FAILED");
            } catch (InvalidExamRowException invalid) {
                transactions.failed(id,attempt,"VALIDATION_FAILED");
            } catch (Exception failure) {
                // Infrastructure failure stays recoverable. A later worker cleans and starts from zero.
                try { executions.recoveryRequired(id,attempt); } catch (Exception unavailable) { /* DB outage: updatedAt remains detectable. */ }
                LoggerFactory.getLogger(getClass()).error("import_recovery_required importId={} attempt={}",id,attempt);
                throw new IllegalStateException("Import interrompu ; reprise du worker nécessaire");
            } finally {
                if (local!=null) Files.deleteIfExists(local);
            }
            var finished=executions.get(id);
            if (finished.status().terminal()) cleanup(finished);
            log(finished);
            return finished.status()==ImportStatus.READY ? 0 : 1;
        }
    }
    private void cleanup(ImportExecution e) {
        if (e.sourceDeletedAt()!=null) return;
        try { storage.delete(e.objectKey()); executions.sourceDeleted(e.id()); }
        catch (Exception failure) { LoggerFactory.getLogger(getClass()).warn("import_source_cleanup_pending importId={}",e.id()); }
    }
    private void log(ImportExecution e) {
        LoggerFactory.getLogger(getClass()).info("import_worker_finished importId={} sessionId={} examType={} year={} sessionType={} status={} expectedRows={} readRows={} writtenRows={} progress={} durationMs={} jobExecutionId={} cloudRunExecutionName={}",
            e.id(),e.sessionId(),e.examType(),e.year(),e.sessionType(),e.status(),e.expectedRows(),e.readRows(),e.writtenRows(),
            ImportExecutionResponse.from(e).progressPercent(),e.durationMs(),e.batchJobExecutionId(),e.cloudRunExecutionName());
    }
}
