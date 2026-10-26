package mr.natijati.results.importing.async;

import mr.natijati.results.exam.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ImportWorkerTransactions {
    private final ImportExecutionRepository executions;
    private final ExamSessionService sessions;
    private final JdbcTemplate jdbc;
    public ImportWorkerTransactions(ImportExecutionRepository executions, ExamSessionService sessions, JdbcTemplate jdbc) {
        this.executions=executions; this.sessions=sessions; this.jdbc=jdbc;
    }
    @Transactional
    public void recoverAndValidate(UUID id, int attempt) {
        var e=executions.fence(id,attempt);
        if (e.sessionId()!=null) sessions.markFailed(e.sessionId());
        if (e.batchJobExecutionId()!=null) {
            // A dead job is abandoned, never restarted from its checkpoint. No Batch internals in HTTP.
            jdbc.update("UPDATE batch_step_execution SET status='ABANDONED',exit_code='ABANDONED',exit_message='',end_time=localtimestamp,last_updated=localtimestamp,version=version+1 WHERE job_execution_id=? AND status IN ('STARTING','STARTED','STOPPING','UNKNOWN')",e.batchJobExecutionId());
            jdbc.update("UPDATE batch_job_execution SET status='ABANDONED',exit_code='ABANDONED',exit_message='',end_time=localtimestamp,last_updated=localtimestamp,version=version+1 WHERE job_execution_id=? AND status IN ('STARTING','STARTED','STOPPING','UNKNOWN')",e.batchJobExecutionId());
        }
        executions.validating(id,attempt);
    }
    @Transactional
    public long prepare(UUID id, int attempt, long rows, int ignored) {
        var e=executions.fence(id,attempt);
        var session=sessions.prepareSession(e.examType(),e.year(),e.sessionType(),id.toString());
        executions.importing(id,attempt,session.getId(),rows,ignored);
        return session.getId();
    }
    @Transactional
    public void beforeBatch(UUID id, int attempt, long batchId) {
        var e=executions.fence(id,attempt);
        sessions.markImporting(e.sessionId());
        executions.batch(id,attempt,batchId);
    }
    @Transactional
    public void ready(UUID id, int attempt) {
        var e=executions.fence(id,attempt);
        if (e.expectedRows()==null || e.writtenRows()!=e.expectedRows()) throw new IllegalStateException("Comptage import incohérent");
        sessions.markReady(e.sessionId());
        if (sessions.getRequired(e.sessionId()).getResultCount()!=e.expectedRows())
            throw new IllegalStateException("Comptage métier incohérent");
        executions.finish(id,attempt,true,null,null);
    }
    @Transactional
    public void failed(UUID id, int attempt, String code) {
        var e=executions.fence(id,attempt);
        if (e.sessionId()!=null) sessions.markFailed(e.sessionId());
        executions.finish(id,attempt,false,code,
            code.equals("VALIDATION_FAILED") ? "Fichier invalide ; corriger puis soumettre un nouveau fichier" : "Import échoué ; soumettre un nouveau fichier");
    }
}
