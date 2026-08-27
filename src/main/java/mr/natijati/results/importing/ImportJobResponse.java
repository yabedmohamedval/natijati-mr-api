package mr.natijati.results.importing;

public record ImportJobResponse(
    Long sessionId,
    Long jobExecutionId,
    String batchStatus,
    String sessionStatus,
    long importedRows
) {
}
