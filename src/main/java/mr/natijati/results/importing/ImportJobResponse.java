package mr.natijati.results.importing;

import java.util.List;

public record ImportJobResponse(
    Long sessionId, Long jobExecutionId, String batchStatus, String sessionStatus, long importedRows,
    long readRows, long rejectedRows, long durationMs, List<ImportIssue> errors, int ignoredColumns
) { }
