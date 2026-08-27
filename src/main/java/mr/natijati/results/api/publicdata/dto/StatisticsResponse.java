package mr.natijati.results.api.publicdata.dto;

import java.math.BigDecimal;
import java.util.List;

public record StatisticsResponse(
    String examType,
    Integer year,
    long candidateCount,
    long admittedCount,
    long failedCount,
    long secondSessionCount,
    long cancelledCount,
    BigDecimal averageScore,
    double successRate,
    List<DistributionItemResponse> byWilaya,
    List<DistributionItemResponse> bySeries
) {
}
