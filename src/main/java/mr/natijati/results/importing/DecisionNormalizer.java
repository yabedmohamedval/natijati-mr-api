package mr.natijati.results.importing;

import mr.natijati.results.exam.DecisionCode;

import java.text.Normalizer;
import java.util.Locale;

public final class DecisionNormalizer {

    private DecisionNormalizer() {
    }

    public static DecisionCode normalize(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DecisionCode.UNKNOWN;
        }

        String value = Normalizer.normalize(rawValue, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .trim()
            .toUpperCase(Locale.ROOT);

        if (value.equals("ADMIS") || value.equals("ADMISE")) {
            return DecisionCode.ADMITTED;
        }
        if (value.equals("AJOURNE") || value.equals("AJOURNEE")) {
            return DecisionCode.FAILED;
        }
        if (value.contains("SESSIONNAIRE")) {
            return DecisionCode.SECOND_SESSION;
        }
        if (value.contains("ANNULATION")) {
            return DecisionCode.CANCELLED;
        }
        if (value.contains("ABSENT")) {
            return DecisionCode.ABSENT;
        }

        return DecisionCode.UNKNOWN;
    }
}
