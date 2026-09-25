package mr.natijati.results.importing;

import java.text.Normalizer;
import java.util.Locale;

public final class ExcelHeaderNormalizer {

    private ExcelHeaderNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        String withoutAccents = Normalizer.normalize(value.replaceAll("\\p{Cf}", ""), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");

        return withoutAccents
            .trim()
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "_")
            .replaceAll("^_+|_+$", "");
    }
}
