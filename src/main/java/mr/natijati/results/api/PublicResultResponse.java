package mr.natijati.results.api;

import java.math.BigDecimal;

public record PublicResultResponse(
    String examType,
    Integer year,
    String identifier,
    String nameFr,
    String nameAr,
    String wilayaFr,
    String wilayaAr,
    String institutionFr,
    String institutionAr,
    String examCenterFr,
    String examCenterAr,
    String seriesCode,
    String seriesFr,
    String seriesAr,
    BigDecimal score,
    String scoreLabel,
    String decisionCode,
    String decisionLabel
) {
}
