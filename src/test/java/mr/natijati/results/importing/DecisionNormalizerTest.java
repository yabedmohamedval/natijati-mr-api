package mr.natijati.results.importing;

import mr.natijati.results.exam.DecisionCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionNormalizerTest {

    @Test
    void shouldNormalizeKnownDecisions() {
        assertThat(DecisionNormalizer.normalize("Admis"))
            .isEqualTo(DecisionCode.ADMITTED);

        assertThat(DecisionNormalizer.normalize("Ajourné"))
            .isEqualTo(DecisionCode.FAILED);

        assertThat(DecisionNormalizer.normalize("Sessionnaire"))
            .isEqualTo(DecisionCode.SECOND_SESSION);

        assertThat(DecisionNormalizer.normalize("Annulation de l’examen"))
            .isEqualTo(DecisionCode.CANCELLED);
    }
}
