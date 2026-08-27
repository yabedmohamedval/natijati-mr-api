package mr.natijati.results.importing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelHeaderNormalizerTest {

    @Test
    void shouldNormalizeFrenchAndUnderscoreHeaders() {
        assertThat(ExcelHeaderNormalizer.normalize("Centre Examen  FR"))
            .isEqualTo("CENTRE_EXAMEN_FR");

        assertThat(ExcelHeaderNormalizer.normalize("Numéro_C1AS"))
            .isEqualTo("NUMERO_C1AS");
    }

    @Test
    void shouldPreserveArabicLetters() {
        assertThat(ExcelHeaderNormalizer.normalize("Centre Examen_AR"))
            .isEqualTo("CENTRE_EXAMEN_AR");
    }
}
