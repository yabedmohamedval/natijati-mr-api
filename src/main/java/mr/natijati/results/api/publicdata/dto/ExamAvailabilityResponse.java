package mr.natijati.results.api.publicdata.dto;

import java.util.List;

public record ExamAvailabilityResponse(
    String examType,
    boolean available,
    List<ExamSessionView> sessions
) {
}
