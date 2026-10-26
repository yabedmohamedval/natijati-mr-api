package mr.natijati.results.importing.async;

import mr.natijati.results.exam.*;
import mr.natijati.results.api.ResultNotFoundException;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

// JdbcTemplate already translates SQL errors; preserve domain exceptions verbatim.
@Component
public class ImportExecutionRepository {
    private final JdbcTemplate jdbc;
    public ImportExecutionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException { return rs.getObject(column, OffsetDateTime.class); }
    private final RowMapper<ImportExecution> mapper = (r, n) -> new ImportExecution(
        r.getObject("id", UUID.class), ExamType.valueOf(r.getString("exam_type")), r.getInt("exam_year"),
        SessionType.valueOf(r.getString("session_type")), ImportStatus.valueOf(r.getString("status")), r.getString("object_key"),
        r.getObject("expected_rows", Long.class), r.getLong("read_rows"), r.getLong("written_rows"), r.getLong("rejected_rows"),
        r.getInt("ignored_columns"), r.getObject("session_id", Long.class), r.getObject("batch_job_execution_id", Long.class),
        r.getString("cloud_run_execution_name"), r.getInt("attempt_count"), time(r,"created_at"), time(r,"started_at"),
        time(r,"updated_at"), time(r,"finished_at"), r.getObject("duration_ms", Long.class), r.getString("error_code"),
        r.getString("error_summary"), time(r,"source_deleted_at"));

    public ImportExecution get(UUID id) {
        return jdbc.query("SELECT * FROM import_execution WHERE id=?", mapper, id).stream().findFirst()
            .orElseThrow(() -> new ResultNotFoundException("Import introuvable"));
    }
    public List<ImportExecutionResponse> recent(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit doit être compris entre 1 et 100");
        return jdbc.query("SELECT * FROM import_execution ORDER BY created_at DESC,id LIMIT ?", mapper, limit)
            .stream().map(ImportExecutionResponse::from).toList();
    }
    @Transactional
    public ImportExecution create(UUID id, ExamType exam, int year, SessionType type, String key) {
        // Same transaction lock as ExamSessionService: includes sessions not created yet.
        Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class,
            exam.name()+":"+year+":"+type.name());
        if (!Boolean.TRUE.equals(acquired)) throw new IllegalStateException("Session réservée par un autre import");
        Integer reserved = jdbc.queryForObject("SELECT count(*) FROM exam_session WHERE exam_type=? AND exam_year=? AND session_type=? AND status IN ('IMPORTING','READY','PUBLISHED')",
            Integer.class, exam.name(), year, type.name());
        if (reserved != null && reserved > 0) throw new IllegalStateException("Session déjà en cours, prête ou publiée : remplacement interdit");
        Integer active = jdbc.queryForObject("SELECT count(*) FROM import_execution WHERE exam_type=? AND exam_year=? AND session_type=? AND status IN ('QUEUED','STARTING','VALIDATING','IMPORTING')",
            Integer.class, exam.name(), year, type.name());
        if (active != null && active > 0) throw new IllegalStateException("Un import réserve déjà cette session");
        jdbc.update("INSERT INTO import_execution(id,exam_type,exam_year,session_type,status,object_key) VALUES(?,?,?,?,'QUEUED',?)",
            id, exam.name(), year, type.name(), key);
        return get(id);
    }
    /** Must run inside the caller transaction. Locks/fences all chunk and session mutations. */
    public ImportExecution fence(UUID id, int attempt) {
        ImportExecution e = jdbc.query("SELECT * FROM import_execution WHERE id=? FOR UPDATE", mapper, id).getFirst();
        if (e.attemptCount() != attempt || e.status().terminal()) throw new IllegalStateException("Tentative import inactive");
        return e;
    }
    @Transactional
    public ImportExecution claim(UUID id, String executionName) {
        // Caller owns the worker-wide PostgreSQL advisory lock.
        ImportExecution e = jdbc.query("SELECT * FROM import_execution WHERE id=? FOR UPDATE", mapper, id).stream().findFirst()
            .orElseThrow(() -> new ResultNotFoundException("Import introuvable"));
        if (e.status().terminal()) return e;
        jdbc.update("UPDATE import_execution SET status='STARTING',attempt_count=attempt_count+1,started_at=coalesce(started_at,now()),updated_at=now(),cloud_run_execution_name=?,error_code=NULL,error_summary=NULL WHERE id=?", executionName, id);
        return get(id);
    }
    public void validating(UUID id, int attempt) {
        requireStatus(fence(id, attempt), ImportStatus.STARTING);
        jdbc.update("UPDATE import_execution SET status='VALIDATING',read_rows=0,written_rows=0,rejected_rows=0,expected_rows=NULL,batch_job_execution_id=NULL,updated_at=now() WHERE id=?", id);
    }
    public void importing(UUID id, int attempt, long session, long expected, int ignored) {
        requireStatus(fence(id, attempt), ImportStatus.VALIDATING);
        jdbc.update("UPDATE import_execution SET status='IMPORTING',session_id=?,expected_rows=?,ignored_columns=?,updated_at=now() WHERE id=?",session,expected,ignored,id);
    }
    public void batch(UUID id, int attempt, long batchId) {
        requireStatus(fence(id, attempt), ImportStatus.IMPORTING);
        jdbc.update("UPDATE import_execution SET batch_job_execution_id=?,updated_at=now() WHERE id=?",batchId,id);
    }
    /** Same transaction as the chunk: visible exactly when its results commit, never per row. */
    public void progress(UUID id, int attempt, long rows) {
        requireStatus(fence(id, attempt), ImportStatus.IMPORTING);
        jdbc.update("UPDATE import_execution SET read_rows=read_rows+?,written_rows=written_rows+?,updated_at=now() WHERE id=?", rows,rows,id);
    }
    public void finish(UUID id, int attempt, boolean ready, String code, String summary) {
        fence(id, attempt);
        jdbc.update("UPDATE import_execution SET status=?,error_code=?,error_summary=?,finished_at=now(),updated_at=now(),duration_ms=(extract(epoch FROM (now()-started_at))*1000)::bigint WHERE id=?",
            ready ? "READY" : "FAILED",code,summary,id);
    }
    private void requireStatus(ImportExecution execution, ImportStatus status) {
        if (execution.status()!=status) throw new IllegalStateException("Transition import invalide");
    }
    public void dispatched(UUID id, String operation) {
        jdbc.update("UPDATE import_execution SET dispatch_operation_name=? WHERE id=?", operation,id);
    }
    public void dispatchUnconfirmed(UUID id) {
        jdbc.update("UPDATE import_execution SET error_code='DISPATCH_UNCONFIRMED',error_summary='Déclenchement non confirmé ; relancer le worker avec cet importId',updated_at=now() WHERE id=? AND status='QUEUED'",id);
    }
    public void recoveryRequired(UUID id, int attempt) {
        jdbc.update("UPDATE import_execution SET error_code='RECOVERY_REQUIRED',error_summary='Worker interrompu ; relancer avec cet importId',updated_at=now() WHERE id=? AND attempt_count=? AND status IN ('STARTING','VALIDATING','IMPORTING')",id,attempt);
    }
    public void sourceDeleted(UUID id) { jdbc.update("UPDATE import_execution SET source_deleted_at=now() WHERE id=?",id); }
}
