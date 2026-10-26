package mr.natijati.results.importing.async;

import mr.natijati.results.exam.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistent JDBC aggregate; contains no workbook values or original filename. */
public record ImportExecution(UUID id, ExamType examType, int year, SessionType sessionType,
    ImportStatus status, String objectKey, Long expectedRows, long readRows, long writtenRows,
    long rejectedRows, int ignoredColumns, Long sessionId, Long batchJobExecutionId,
    String cloudRunExecutionName, int attemptCount, OffsetDateTime createdAt, OffsetDateTime startedAt,
    OffsetDateTime updatedAt, OffsetDateTime finishedAt, Long durationMs, String errorCode,
    String errorSummary, OffsetDateTime sourceDeletedAt) { }
