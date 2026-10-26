package mr.natijati.results.importing.async;

import org.springframework.batch.core.*;
import org.springframework.batch.core.scope.context.ChunkContext;
import java.util.UUID;

/** Locks before any chunk read/write, fencing stale attempts until commit. */
public class ImportProgressListener implements ChunkListener {
    private final ImportExecutionRepository executions;
    public ImportProgressListener(ImportExecutionRepository executions) { this.executions=executions; }
    @Override public void afterChunk(ChunkContext context) {
        var params=context.getStepContext().getStepExecution().getJobParameters();
        if (params.getString("importId")!=null) {
            var e=executions.get(UUID.fromString(params.getString("importId")));
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "import_progress importId={} sessionId={} examType={} year={} sessionType={} status={} expectedRows={} readRows={} writtenRows={} progress={} jobExecutionId={}",
                e.id(),e.sessionId(),e.examType(),e.year(),e.sessionType(),e.status(),e.expectedRows(),e.readRows(),e.writtenRows(),
                ImportExecutionResponse.from(e).progressPercent(),e.batchJobExecutionId());
        }
    }
    @Override public void beforeChunk(ChunkContext context) {
        var params=context.getStepContext().getStepExecution().getJobParameters();
        if (params.getString("importId")!=null)
            executions.fence(UUID.fromString(params.getString("importId")),params.getLong("importAttempt").intValue());
    }
}
