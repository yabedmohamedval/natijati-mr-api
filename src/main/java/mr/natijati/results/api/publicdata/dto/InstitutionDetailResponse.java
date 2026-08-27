package mr.natijati.results.api.publicdata.dto;

import java.util.List;

public record InstitutionDetailResponse(
    InstitutionSummaryResponse summary,
    List<DistributionItemResponse> decisionDistribution,
    List<DistributionItemResponse> seriesDistribution
) {
}
