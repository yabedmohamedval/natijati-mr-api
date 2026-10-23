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
        if (value.equals("SESSIONNAIRE")) {
            return DecisionCode.SECOND_SESSION;
        }
        if ((value.equals("ANNULATION") || value.equals("ANNULATION DE L’EXAMEN") || value.equals("ANNULATION DE L\'EXAMEN"))) {
            return DecisionCode.CANCELLED;
        }
        if ((value.equals("ABSENT") || value.equals("ABSENTE"))) {
            return DecisionCode.ABSENT;
        }
        // Exact aliases observed in the official BEPC 2026 file; no fuzzy match.
        if (value.equals("EXAMEN ANNULE -TELEPHONE") || value.equals("EXAMEN-ANNULE-TELEPHONE")) {
            return DecisionCode.CANCELLED;
        }
        if (value.equals("ELIMINE")) {
            return DecisionCode.ELIMINATED;
        }

        return DecisionCode.UNKNOWN;
    }
}
