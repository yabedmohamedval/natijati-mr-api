package mr.natijati.results.importing;

import java.util.List;

/** Legacy engine report retained for manual/historical test tooling; no longer the HTTP contract. */
public record ImportJobResponse(
    Long sessionId, Long jobExecutionId, String batchStatus, String sessionStatus, long importedRows,
    long readRows, long rejectedRows, long durationMs, List<ImportIssue> errors, int ignoredColumns
) { }
