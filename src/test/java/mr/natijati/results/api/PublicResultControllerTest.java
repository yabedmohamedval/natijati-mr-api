package mr.natijati.results.api;

import mr.natijati.results.exam.*;
import mr.natijati.results.result.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicResultControllerTest {
    private final BacResultRepository bac = mock(BacResultRepository.class);
    private final BepcResultRepository bepc = mock(BepcResultRepository.class);
    private final C1asResultRepository c1as = mock(C1asResultRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PublicResultController(bac, bepc, c1as))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({"BAC,NORMAL", "BAC,COMPLEMENTARY", "BEPC,NORMAL", "BEPC,COMPLEMENTARY",
        "C1AS,NORMAL", "C1AS,COMPLEMENTARY"})
    void looksUpThePublishedResultForTheExactExamAndSession(ExamType exam, SessionType session) throws Exception {
        switch (exam) {
            case BAC -> {
                BacResult result = mock(BacResult.class);
                when(result.getCandidateNumber()).thenReturn(123L);
                when(bac.findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndCandidateNumber(
                    exam, 2026, session, PublicationStatus.PUBLISHED, 123L)).thenReturn(Optional.of(result));
            }
            case BEPC -> {
                BepcResult result = mock(BepcResult.class);
                when(result.getCandidateNumber()).thenReturn(123L);
                when(bepc.findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndCandidateNumber(
                    exam, 2026, session, PublicationStatus.PUBLISHED, 123L)).thenReturn(Optional.of(result));
            }
            case C1AS -> {
                C1asResult result = mock(C1asResult.class);
                when(result.getDossierNumber()).thenReturn(123L);
                when(c1as.findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndDossierNumber(
                    exam, 2026, session, PublicationStatus.PUBLISHED, 123L)).thenReturn(Optional.of(result));
            }
        }
        String path = "/api/public/results/" + exam.name().toLowerCase() + "/2026/"
            + (exam == ExamType.C1AS ? "dossier/" : "") + "123";
        // The legacy URL must still default to NORMAL.
        mvc.perform(get(path).queryParam("sessionType", session.name()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.examType").value(exam.name()))
            .andExpect(jsonPath("$.identifier").value("123"))
            .andExpect(jsonPath("$.sessionType").value(session.name()))
            .andExpect(jsonPath("$.scoreLabel").value(exam == ExamType.C1AS ? "Total" : "Moyenne"));
        mvc.perform(get(path)).andExpect(session == SessionType.NORMAL ? status().isOk() : status().isNotFound());
        mvc.perform(get(path).queryParam("sessionType", "UNKNOWN")).andExpect(status().isBadRequest());
        switch (exam) {
            case BAC -> verifyNoInteractions(bepc, c1as);
            case BEPC -> verifyNoInteractions(bac, c1as);
            case C1AS -> verifyNoInteractions(bac, bepc);
        }
    }
}
