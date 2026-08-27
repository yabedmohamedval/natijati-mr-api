package mr.natijati.results.importing;

import mr.natijati.results.exam.DecisionCode;

import java.math.BigDecimal;

public record BacResultCommand(
    Long candidateNumber,
    String nameFr,
    String nameAr,
    String wilayaFr,
    String wilayaAr,
    String examCenterFr,
    String examCenterAr,
    String institutionFr,
    String institutionAr,
    String seriesCode,
    String seriesFr,
    String seriesAr,
    BigDecimal score,
    DecisionCode decisionCode,
    String decisionOriginal
) implements ExamResultCommand {
}
