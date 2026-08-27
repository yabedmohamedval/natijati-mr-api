package mr.natijati.results.importing;

import mr.natijati.results.exam.DecisionCode;

import java.math.BigDecimal;

public record BepcResultCommand(
    Long candidateNumber,
    String nameFr,
    Integer wilayaCode,
    String wilayaFr,
    String examCenterFr,
    BigDecimal score,
    DecisionCode decisionCode,
    String decisionOriginal
) implements ExamResultCommand {
}
