package mr.natijati.results.exam;

public record ExamSessionResponse(
    Long id,
    ExamType examType,
    Integer year,
    SessionType sessionType,
    PublicationStatus status,
    String sourceFilename,
    long resultCount
) {
    public static ExamSessionResponse from(ExamSession session) {
        return new ExamSessionResponse(
            session.getId(),
            session.getExamType(),
            session.getYear(),
            session.getSessionType(),
            session.getStatus(),
            session.getSourceFilename(),
            session.getResultCount()
        );
    }
}
