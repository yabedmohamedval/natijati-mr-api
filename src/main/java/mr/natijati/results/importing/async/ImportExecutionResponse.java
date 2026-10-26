package mr.natijati.results.importing.async;

import mr.natijati.results.exam.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

public record ImportExecutionResponse(UUID importId, ExamType examType, int year, SessionType sessionType,
    ImportStatus status, Long expectedRows, long readRows, long writtenRows, long rejectedRows,
    BigDecimal progressPercent, Long sessionId, Long batchJobExecutionId, int attemptCount,
    OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime updatedAt,
    OffsetDateTime finishedAt, Long durationMs, List<Error> errors) {
    public record Error(String code, String message) { }
    public static ImportExecutionResponse from(ImportExecution e) {
        BigDecimal progress = e.expectedRows() == null || e.expectedRows() <= 0 ? null :
            BigDecimal.valueOf(e.writtenRows()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(e.expectedRows()), 2, RoundingMode.HALF_UP);
        return new ImportExecutionResponse(e.id(), e.examType(), e.year(), e.sessionType(), e.status(),
            e.expectedRows(), e.readRows(), e.writtenRows(), e.rejectedRows(), progress, e.sessionId(),
            e.batchJobExecutionId(), e.attemptCount(), e.createdAt(), e.startedAt(), e.updatedAt(),
            e.finishedAt(), e.durationMs(), e.errorCode() == null ? List.of() : List.of(new Error(e.errorCode(), e.errorSummary())));
    }
}
