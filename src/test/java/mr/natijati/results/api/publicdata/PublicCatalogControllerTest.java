package mr.natijati.results.api.publicdata;

import mr.natijati.results.api.publicdata.dto.SearchCriteria;
import mr.natijati.results.exam.SessionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicCatalogControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "NORMAL", "COMPLEMENTARY"})
    void forwardsTheSessionToEveryCatalogRead(String querySession) throws Exception {
        var service = mock(PublicCatalogService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PublicCatalogController(service)).build();
        SessionType expected = querySession.isEmpty() ? SessionType.NORMAL : SessionType.valueOf(querySession);
        String suffix = querySession.isEmpty() ? "" : "&sessionType=" + querySession;
        mvc.perform(get("/api/public/catalog/search?examType=C1AS&year=2025&candidateNumber=98765" + suffix))
            .andExpect(status().isOk());
        verify(service).search(argThat((SearchCriteria c) -> c.examType().equals("C1AS") && c.year() == 2025
            && c.candidateNumber().equals("98765") && c.sessionType() == expected));
        mvc.perform(get("/api/public/catalog/statistics/BEPC/2025?" + suffix)).andExpect(status().isOk());
        verify(service).statistics("BEPC", 2025, expected);
        mvc.perform(get("/api/public/catalog/institutions?examType=BAC&year=2025" + suffix)).andExpect(status().isOk());
        verify(service).institutions("BAC", 2025, null, 0, 20, expected);
        mvc.perform(get("/api/public/catalog/institutions/school-id?" + suffix)).andExpect(status().isOk());
        verify(service).institution("school-id", expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/search?examType=BAC&year=2026", "/statistics/BAC/2026?", "/institutions?examType=BAC&year=2026", "/institutions/id?"})
    void rejectsUnknownSessionsBeforeAccessingData(String path) throws Exception {
        var service = mock(PublicCatalogService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PublicCatalogController(service)).build();
        mvc.perform(get("/api/public/catalog" + path + "&sessionType=UNKNOWN")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
