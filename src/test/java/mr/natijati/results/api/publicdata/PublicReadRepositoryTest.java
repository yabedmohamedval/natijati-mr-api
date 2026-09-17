package mr.natijati.results.api.publicdata;

import mr.natijati.results.api.publicdata.dto.SearchCriteria;
import mr.natijati.results.exam.SessionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PublicReadRepositoryTest {
    @ParameterizedTest
    @CsvSource({"BAC,NORMAL", "BAC,COMPLEMENTARY", "BEPC,NORMAL", "BEPC,COMPLEMENTARY",
        "C1AS,NORMAL", "C1AS,COMPLEMENTARY"})
    void searchUsesThePublicIdentifierAndScopesBothCountAndPage(String exam, SessionType session) throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
            .thenAnswer(call -> {
                ResultSet row = mock(ResultSet.class);
                when(row.getString("exam_type")).thenReturn(exam);
                when(row.getString("session_type")).thenReturn(session.name());
                when(row.getString("identifier")).thenReturn("98765");
                when(row.getInt("year")).thenReturn(2026);
                return List.of(call.<RowMapper<?>>getArgument(2).mapRow(row, 0));
            });
        var repository = new PublicReadRepository(jdbc, new InstitutionKeyCodec());
        var page = repository.search(new SearchCriteria(exam, 2026, "98765", null, null, null, null, 1, 10, session));
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.identifier()).isEqualTo("98765");
            assertThat(item.sessionType()).isEqualTo(session);
        });
        assertThat(page.totalElements()).isEqualTo(1);
        String column = exam.equals("C1AS") ? "dossier_number" : "candidate_number";
        mockingDetails(jdbc).getInvocations().forEach(call -> {
            String sql = call.getArgument(0);
            SqlParameterSource params = call.getArgument(1);
            assertScope(sql, params, session);
            assertThat(sql).contains("br." + column + "::text AS identifier", "br." + column + "::text = :candidateNumber");
            assertThat(params.getValue("candidateNumber")).isEqualTo("98765");
            if (exam.equals("C1AS")) assertThat(sql).doesNotContain("br.candidate_number");
        });
    }

    @ParameterizedTest
    @CsvSource({"BAC,NORMAL", "BAC,COMPLEMENTARY", "BEPC,NORMAL", "BEPC,COMPLEMENTARY",
        "C1AS,NORMAL", "C1AS,COMPLEMENTARY"})
    void everyAggregateAndInstitutionDetailQueryStaysWithinTheSelectedSession(String exam, SessionType session) throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), any(SqlParameterSource.class))).thenReturn(Map.of("candidate_count", 1L));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
            .thenAnswer(call -> {
                ResultSet row = mock(ResultSet.class);
                when(row.getString("name_fr")).thenReturn("School");
                when(row.getLong("candidate_count")).thenReturn(1L);
                when(row.getBigDecimal("average_score")).thenReturn(BigDecimal.TEN);
                return List.of(call.<RowMapper<?>>getArgument(2).mapRow(row, 0));
            });
        InstitutionKeyCodec codec = new InstitutionKeyCodec();
        var repository = new PublicReadRepository(jdbc, codec);
        assertThat(repository.statistics(exam, 2026, session).sessionType()).isEqualTo(session);
        var detail = repository.findInstitution(codec.encode(exam, 2026, "School"), session);
        assertThat(detail.summary().sessionType()).isEqualTo(session);
        assertThat(mockingDetails(jdbc).getInvocations()).hasSize(exam.equals("BAC") ? 7 : exam.equals("BEPC") ? 5 : 4);
        mockingDetails(jdbc).getInvocations().forEach(call ->
            assertScope(call.getArgument(0), call.getArgument(1), session));
    }

    private static void assertScope(String sql, SqlParameterSource params, SessionType session) {
        assertThat(sql).contains("es.status = 'PUBLISHED'", "es.session_type = :sessionType", "es.exam_year = :year");
        assertThat(params.getValue("sessionType")).isEqualTo(session.name());
        assertThat(params.getValue("year")).isEqualTo(2026);
    }
}
