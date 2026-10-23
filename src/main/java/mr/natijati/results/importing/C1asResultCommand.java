package mr.natijati.results.importing;

import java.math.BigDecimal;

public record C1asResultCommand(
    Long candidateNumber,
    Long dossierNumber,
    String nameAr,
    String candidateType,
    String schoolAr,
    String wilayaAr,
    String moughataaAr,
    String examCenterAr,
    BigDecimal totalScore,
    String candidateNumberOriginal,
    String dossierNumberOriginal
) implements ExamResultCommand {
}
