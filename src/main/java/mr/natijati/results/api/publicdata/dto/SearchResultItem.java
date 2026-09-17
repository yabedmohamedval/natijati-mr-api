package mr.natijati.results.api.publicdata.dto;

import mr.natijati.results.exam.SessionType;
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
    String decisionLabel,
    SessionType sessionType
) {
}
