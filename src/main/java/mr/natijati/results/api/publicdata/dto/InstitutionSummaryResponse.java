package mr.natijati.results.api.publicdata.dto;

import mr.natijati.results.exam.SessionType;
import java.math.BigDecimal;

public record InstitutionSummaryResponse(
    String id,
    String examType,
    Integer year,
    String nameFr,
    String nameAr,
    String wilayaFr,
    String wilayaAr,
    long candidateCount,
    long admittedCount,
    BigDecimal averageScore,
    double successRate,
    SessionType sessionType
) {
}
