package mr.natijati.results.api.publicdata.dto;

import java.time.LocalDateTime;

public record ExamSessionView(
    Long id,
    String examType,
    Integer year,
    String sessionType,
    String status,
    LocalDateTime publishedAt,
    String sourceFilename,
    Long resultCount
) {
}
