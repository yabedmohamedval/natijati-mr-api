package mr.natijati.results.api.publicdata.dto;

import java.math.BigDecimal;

public record SearchResultItem(
    String examType,
    Integer year,
    String identifier,
    String nameFr,
    String nameAr,
    String wilayaFr,
    String wilayaAr,
    String institutionFr,
    String institutionAr,
    BigDecimal score,
    String decisionCode,
    String decisionLabel
) {
}
