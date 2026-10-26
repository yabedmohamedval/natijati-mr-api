package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamSessionService;
import org.springframework.batch.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.LocalDateTime;

public class ImportJobListener implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(ImportJobListener.class);
    private final ExamSessionService sessions;
    private final mr.natijati.results.importing.async.ImportWorkerTransactions transactions;
    public ImportJobListener(ExamSessionService sessions, mr.natijati.results.importing.async.ImportWorkerTransactions transactions) {
        this.sessions = sessions; this.transactions = transactions;
    }

    @Override public void beforeJob(JobExecution execution) {
        if (execution.getJobParameters().getString("importId") != null) {
            transactions.beforeBatch(java.util.UUID.fromString(execution.getJobParameters().getString("importId")),
                execution.getJobParameters().getLong("importAttempt").intValue(), execution.getId());
        } else sessions.markImporting(execution.getJobParameters().getLong("sessionId"));
    }

    @Override public void afterJob(JobExecution execution) {
        if (execution.getJobParameters().getString("importId") != null) {
            var importId = java.util.UUID.fromString(execution.getJobParameters().getString("importId"));
            int attempt = execution.getJobParameters().getLong("importAttempt").intValue();
            try {
                if (execution.getStatus() == BatchStatus.COMPLETED) transactions.ready(importId, attempt);
                else transactions.failed(importId, attempt, "BATCH_FAILED");
            } catch (RuntimeException failure) {
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
                log.error("import_finalization_recovery_required importId={} jobExecutionId={}", importId, execution.getId());
            }
            return;
        }
        Long id = execution.getJobParameters().getLong("sessionId");
        long written = execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
        try {
            if (execution.getStatus() == BatchStatus.COMPLETED) {
                Long expected = execution.getJobParameters().getLong("expectedRows");
                if (expected != null && expected != written) throw new IllegalStateException("Nombre de lignes différent du contrôle préalable");
                sessions.markReady(id);
            } else {
                sessions.markFailed(id); // bulk delete + FAILED in one transaction
            }
        } catch (RuntimeException ex) {
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(ExitStatus.FAILED);
            execution.addFailureException(new IllegalStateException("Finalisation import impossible"));
            // If cleanup fails too, the reservation stays IMPORTING, never READY/PUBLISHED.
            try { sessions.markFailed(id); }
            catch (RuntimeException cleanup) { log.error("import_cleanup_failed sessionId={} jobExecutionId={}", id, execution.getId()); }
        }
        var session = sessions.getRequired(id);
        log.info("import_finished exam={} year={} session={} sessionId={} jobExecutionId={} status={} read={} committedBeforeCleanup={} retained={} durationMs={}",
            session.getExamType(), session.getYear(), session.getSessionType(), id, execution.getId(), execution.getStatus(),
            execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum(), written, session.getResultCount(),
            execution.getStartTime() == null ? 0 : Duration.between(execution.getStartTime(), LocalDateTime.now()).toMillis());
    }
}
