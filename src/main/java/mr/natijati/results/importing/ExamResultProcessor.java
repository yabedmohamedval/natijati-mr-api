package mr.natijati.results.importing;

import mr.natijati.results.exam.DecisionCode;
import mr.natijati.results.exam.ExamType;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public class ExamResultProcessor
    implements ItemProcessor<RawExamRow, ExamResultCommand> {

    private final ExamType examType;

    public ExamResultProcessor(ExamType examType) {
        this.examType = examType;
    }

    @Override
    public ExamResultCommand process(RawExamRow row) {
        return switch (examType) {
            case BAC -> mapBac(row);
            case BEPC -> mapBepc(row);
            case C1AS -> mapC1as(row);
        };
    }

    private BacResultCommand mapBac(RawExamRow row) {
        String decision = value(row, "DECISION");

        return new BacResultCommand(
            requiredLong(row, "NUM_BAC"),
            requiredText(row, "NOM_FR"),
            value(row, "NOM_AR"),
            value(row, "WILAYA_FR"),
            value(row, "WILAYA_AR"),
            value(row, "CENTRE_EXAMEN_FR"),
            value(row, "CENTRE_EXAMEN_AR"),
            value(row, "ETABLISSEMENT_FR"),
            value(row, "ETABLISSEMENT_AR"),
            value(row, "SERIE"),
            value(row, "SERIE_FR"),
            value(row, "SERIE_AR"),
            score(row, "MOY_BAC", decision),
            decision(row, decision),
            decision
        );
    }

    private BepcResultCommand mapBepc(RawExamRow row) {
        String decision = value(row, "DECISION");

        return new BepcResultCommand(
            requiredLong(row, "NUMERO"),
            requiredText(row, "NOM"),
            optionalInteger(row, "CODE_WILAYA"),
            value(row, "WILAYA"),
            value(row, "CENTRE"),
            score(row, "MOYG", decision),
            decision(row, decision),
            decision,
            value(row, "NUMERO")
        );
    }

    private C1asResultCommand mapC1as(RawExamRow row) {
        return new C1asResultCommand(
            optionalLong(row, "NUMERO_C1AS"),
            requiredLong(row, "NODOSS"),
            requiredText(row, "NOM_AR"),
            value(row, "TYPE"),
            value(row, "ECOLE_AR"),
            value(row, "WILAYA_AR"),
            value(row, "MOUGHATAA_AR"),
            value(row, "CENTRE_EXAMEN_AR"),
            score(row, "TOTAL", null),
            value(row, "NUMERO_C1AS"),
            value(row, "NODOSS")
        );
    }

    private InvalidExamRowException invalid(RawExamRow row, String field, String reason) {
        return new InvalidExamRowException(examType, row.rowNumber(), field, reason);
    }

    private DecisionCode decision(RawExamRow row, String raw) {
        DecisionCode code = DecisionNormalizer.normalize(raw);
        if (code == DecisionCode.UNKNOWN) throw invalid(row, "DECISION", "Décision absente ou inconnue");
        return code;
    }

    private String requiredText(RawExamRow row, String key) {
        String text = value(row, key);
        if (text.isBlank()) throw invalid(row, key, "Valeur obligatoire");
        return text;
    }

    private Long requiredLong(RawExamRow row, String key) {
        Long number = optionalLong(row, key);
        if (number == null) throw invalid(row, key, "Identifiant obligatoire");
        return number;
    }

    private Long optionalLong(RawExamRow row, String key) {
        String text = value(row, key);
        if (text.isBlank()) return null;
        try {
            long number = decimal(text).longValueExact();
            if (number <= 0) throw invalid(row, key, "Entier strictement positif requis");
            return number;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw invalid(row, key, "Entier invalide ou hors capacité");
        }
    }

    private Integer optionalInteger(RawExamRow row, String key) {
        Long number = optionalLong(row, key);
        if (number == null) return null;
        if (number > Integer.MAX_VALUE) throw invalid(row, key, "Entier hors capacité");
        return number.intValue();
    }

    private BigDecimal score(RawExamRow row, String key, String rawDecision) {
        String text = value(row, key);
        if (text.isBlank() && rawDecision != null) {
            DecisionCode code = decision(row, rawDecision);
            if (code == DecisionCode.ABSENT || code == DecisionCode.CANCELLED) return null;
        }
        if (text.isBlank()) throw invalid(row, key, "Score obligatoire");
        try {
            BigDecimal score = decimal(text).stripTrailingZeros();
            if (score.signum() < 0 || (examType != ExamType.C1AS && score.compareTo(BigDecimal.valueOf(20)) > 0))
                throw invalid(row, key, "Score hors plage");
            // BEPC's official XML contains genuine precision beyond 12 decimals.
            int scale = examType == ExamType.BEPC ? 18 : 12;
            if ((long) score.precision() - score.scale() > 6)
                throw invalid(row, key, "Score incompatible avec NUMERIC(" + (scale + 6) + "," + scale + ")");
            if (score.scale() > scale) {
                BigDecimal stored = score.setScale(scale, RoundingMode.HALF_EVEN).stripTrailingZeros();
                // At most half a binary64 ULP, capped at 1e-14 score points.
                // Only the scores use this tolerance; identifiers remain exact integers.
                BigDecimal tolerance = BigDecimal.valueOf(Math.ulp(score.doubleValue()))
                    .divide(BigDecimal.valueOf(2)).min(new BigDecimal("1E-14"));
                if ((stored.signum() == 0 && score.signum() != 0) || score.subtract(stored).abs().compareTo(tolerance) > 0)
                    throw invalid(row, key, "Score incompatible avec NUMERIC(" + (scale + 6) + "," + scale + ")");
                score = stored;
            }
            if ((long) score.precision() - score.scale() > 6)
                throw invalid(row, key, "Score incompatible avec NUMERIC(" + (scale + 6) + "," + scale + ")");
            return score;
        } catch (NumberFormatException ex) {
            throw invalid(row, key, "Score numérique invalide");
        }
    }

    private BigDecimal decimal(String value) {
        String normalized = value.replace("\u00a0", "").replace(" ", "").replace(',', '.');
        if (normalized.length() > 64) throw new NumberFormatException("Nombre trop long");
        BigDecimal number = new BigDecimal(normalized);
        if (number.scale() < -1000 || number.scale() > 1000) throw new NumberFormatException("Exposant hors limites");
        return number;
    }

    private String value(RawExamRow row, String key) {
        String text = row.values().getOrDefault(key, "").trim();
        int max = switch (key) {
            case "SERIE", "TYPE" -> 30;
            case "WILAYA_FR", "WILAYA_AR", "WILAYA", "MOUGHATAA_AR", "SERIE_FR", "SERIE_AR", "DECISION" -> 150;
            default -> 255;
        };
        if (text.length() > max) throw invalid(row, key, "Texte trop long (maximum " + max + ")");
        return text;
    }
}
