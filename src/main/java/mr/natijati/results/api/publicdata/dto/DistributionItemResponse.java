package mr.natijati.results.api.publicdata.dto;

public record DistributionItemResponse(
    String code,
    String labelFr,
    String labelAr,
    long count,
    double percentage
) {
}
