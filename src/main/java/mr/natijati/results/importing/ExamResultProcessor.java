package mr.natijati.results.importing;

import mr.natijati.results.exam.DecisionCode;
import mr.natijati.results.exam.ExamType;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
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
            value(row, "NOM_FR"),
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
            optionalDecimal(row, "MOY_BAC"),
            DecisionNormalizer.normalize(decision),
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
            optionalDecimal(row, "MOYG"),
            DecisionNormalizer.normalize(decision),
            decision
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
            optionalDecimal(row, "TOTAL")
        );
    }

    private String requiredText(RawExamRow row, String key) {
        String value = value(row, key);
        if (value.isBlank()) {
            throw new InvalidExamRowException(
                row.rowNumber(),
                "la colonne " + key + " est vide"
            );
        }
        return value;
    }

    private Long requiredLong(RawExamRow row, String key) {
        Long value = optionalLong(row, key);
        if (value == null) {
            throw new InvalidExamRowException(
                row.rowNumber(),
                "la colonne " + key + " est vide"
            );
        }
        return value;
    }

    private Long optionalLong(RawExamRow row, String key) {
        String value = value(row, key);
        if (value.isBlank()) {
            return null;
        }

        try {
            return decimal(value).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new InvalidExamRowException(
                row.rowNumber(),
                key + " n'est pas un entier valide : " + value,
                exception
            );
        }
    }

    private Integer optionalInteger(RawExamRow row, String key) {
        Long value = optionalLong(row, key);
        return value == null ? null : Math.toIntExact(value);
    }

    private BigDecimal optionalDecimal(RawExamRow row, String key) {
        String value = value(row, key);
        if (value.isBlank()) {
            return null;
        }

        try {
            return decimal(value);
        } catch (NumberFormatException exception) {
            throw new InvalidExamRowException(
                row.rowNumber(),
                key + " n'est pas un nombre valide : " + value,
                exception
            );
        }
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(
            value.replace("\u00a0", "")
                .replace(" ", "")
                .replace(',', '.')
        );
    }

    private String value(RawExamRow row, String key) {
        Map<String, String> values = row.values();
        return values.getOrDefault(key, "").trim();
    }
}
