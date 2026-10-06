package mr.natijati.results.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import mr.natijati.results.exam.*;
import mr.natijati.results.result.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
    "spring.config.location=classpath:/application.yml,classpath:/application-integration.properties",
    "spring.profiles.active=integration",
    "DB_POOL_SIZE=3", "DB_MIN_IDLE=1",
    "DB_CONNECTION_TIMEOUT_MS=12345", "DB_IDLE_TIMEOUT_MS=120000", "DB_MAX_LIFETIME_MS=600000",
    // Fail closed if ServiceConnection is accidentally removed. Never consult DB_URL.
    "spring.datasource.url=jdbc:disabled:integration-only",
    "spring.datasource.username=unused", "spring.datasource.password=unused",
    "spring.flyway.url=", "spring.flyway.user=", "spring.flyway.password=",
    "app.admin.username=integration-admin", "app.admin.password=synthetic-test-password"
})
@AutoConfigureMockMvc
@Transactional
class BackendIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-alpine")
        .withDatabaseName("natijati_integration").withUsername("test").withPassword("test");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired JobRepository jobs;
    @Autowired Job examResultImportJob;
    @Autowired BacResultRepository bac;
    @Autowired BepcResultRepository bepc;
    @Autowired C1asResultRepository c1as;
    @Autowired ObjectMapper json;

    @Test
    void hikariBindsEnvironmentSettingsToRealPool() throws Exception {
        var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
        assertThat(pool.getMinimumIdle()).isEqualTo(1);
        assertThat(pool.getConnectionTimeout()).isEqualTo(12345);
        assertThat(pool.getIdleTimeout()).isEqualTo(120000);
        assertThat(pool.getMaxLifetime()).isEqualTo(600000);
        try (var connection = pool.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthIsPublicWithoutDetails(String url) throws Exception {
        mvc.perform(get(url)).andExpect(status().isOk())
            .andExpect(content().json(url.equals("/actuator/health")
                ? "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"
                : "{\"status\":\"UP\"}", true));
    }

    @Test
    void metricsRequireRealAdminCredentials() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(httpBasic("integration-admin", "wrong")))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(httpBasic("integration-admin", "synthetic-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.names").isArray());
        mvc.perform(get("/actuator/metrics/hikaricp.connections.max")
            .with(httpBasic("integration-admin", "synthetic-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.measurements[0].value").value(3.0));
        mvc.perform(get("/actuator/metrics").with(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"info", "env", "beans", "configprops", "heapdump", "loggers", "health/db"})
    void otherActuatorPathsAreDeniedEvenForAdmin(String path) throws Exception {
        mvc.perform(get("/actuator/" + path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/" + path).with(httpBasic("integration-admin", "synthetic-test-password")))
            .andExpect(status().isForbidden());
    }

    @Autowired org.springframework.boot.actuate.health.HealthContributorRegistry healthRegistry;

    @Test
    void databaseFailureAffectsReadinessButNeverLivenessAndDoesNotLeakDetails() throws Exception {
        var realDb = healthRegistry.unregisterContributor("db");
        assertThat(realDb).isNotNull();
        try {
            healthRegistry.registerContributor("db", (org.springframework.boot.actuate.health.HealthIndicator) () ->
                org.springframework.boot.actuate.health.Health.down()
                    .withDetail("error", "synthetic-sensitive-database-message").build());
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", true));
            mvc.perform(get("/actuator/health").with(httpBasic("integration-admin", "synthetic-test-password")))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json(
                    "{\"status\":\"DOWN\",\"groups\":[\"liveness\",\"readiness\"]}", true));
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
        } finally {
            healthRegistry.unregisterContributor("db");
            healthRegistry.registerContributor("db", realDb);
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void defaultErrorResponseExcludesDatabaseMessagesAndStacktraces() throws Exception {
        mvc.perform(get("/error")
            .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500)
            .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION,
                new RuntimeException("synthetic-sensitive-database-message")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.trace").doesNotExist())
            .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void swaggerRemainsPublicIncludingConfiguredShortcut() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").exists())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("synthetic-test-password", postgres.getJdbcUrl()));
    }

    @BeforeEach
    void syntheticFixtures() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        // Same identifiers and institution across sessions; different scores expose accidental mixing.
        for (String exam : new String[]{"BAC", "BEPC", "C1AS"}) {
            for (String session : new String[]{"NORMAL", "COMPLEMENTARY"}) {
                long id = session(exam, 2025, session, "PUBLISHED");
                result(exam, id, 10001, session.equals("NORMAL") ? "12.5" : "14");
                if (session.equals("NORMAL")) result(exam, id, 10002, "7.5");
            }
            result(exam, session(exam, 2024, "NORMAL", "PUBLISHED"), 10001, "9");
            result(exam, session(exam, 2023, "NORMAL", "DRAFT"), 10001, "19");
        }
    }

    private long session(String exam, int year, String session, String status) {
        return jdbc.queryForObject("""
            INSERT INTO exam_session(exam_type,exam_year,session_type,status,result_count)
            VALUES (?,?,?,?,0) RETURNING id
            """, Long.class, exam, year, session, status);
    }

    private void result(String exam, long session, long identifier, String score) {
        switch (exam) {
            case "BAC" -> jdbc.update("""
                INSERT INTO bac_result(exam_session_id,candidate_number,name_fr,institution_fr,wilaya_fr,series_code,score,decision_code)
                VALUES (?,?,'Synthetic candidate','Synthetic school','Synthetic region','SN',?,'ADMITTED')
                """, session, identifier, new BigDecimal(score));
            case "BEPC" -> jdbc.update("""
                INSERT INTO bepc_result(exam_session_id,candidate_number,name_fr,exam_center_fr,wilaya_fr,score,decision_code)
                VALUES (?,?,'Synthetic candidate','Synthetic school','Synthetic region',?,'ADMITTED')
                """, session, identifier, new BigDecimal(score));
            case "C1AS" -> jdbc.update("""
                INSERT INTO c1as_result(exam_session_id,dossier_number,candidate_number,name_ar,school_ar,wilaya_ar,total_score)
                VALUES (?,?,90001,'Synthetic candidate','Synthetic school','Synthetic region',?)
                """, session, identifier, new BigDecimal(score));
            default -> throw new IllegalArgumentException(exam);
        }
    }

    @Test
    void flywaySchemaAndBatchAreReal() throws Exception {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().applied()).map(m -> m.getVersion().toString())).contains("1");
        var tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class);
        assertThat(tables).contains("flyway_schema_history", "exam_session", "bac_result", "bepc_result", "c1as_result",
            "batch_job_instance", "batch_job_execution", "batch_job_execution_params", "batch_step_execution",
            "batch_job_execution_context", "batch_step_execution_context");
        var indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname='public'", String.class);
        assertThat(indexes).contains("uk_exam_session", "uk_bac_result", "uk_bepc_result", "uk_c1as_result_dossier",
            "idx_bac_search", "idx_bepc_search", "idx_c1as_dossier_search", "idx_c1as_candidate_number");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid IN ('bac_result'::regclass,'bepc_result'::regclass,'c1as_result'::regclass)", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success=false", Integer.class)).isZero();
        assertThat(examResultImportJob.getName()).isEqualTo("examResultImportJob");
        assertThat(jobs.isJobInstanceExists("examResultImportJob", new JobParameters())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM batch_job_execution", Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"BAC,NORMAL,12.5", "BAC,COMPLEMENTARY,14", "BEPC,NORMAL,12.5", "BEPC,COMPLEMENTARY,14", "C1AS,NORMAL,12.5", "C1AS,COMPLEMENTARY,14"})
    void jpaFiltersYearSessionStatusAndIdentifier(ExamType exam, SessionType session, String score) {
        assertThat(repositoryScore(exam, 2025, session, 10001)).isEqualByComparingTo(score);
        assertThat(repositoryScore(exam, 2024, SessionType.NORMAL, 10001)).isEqualByComparingTo("9");
        assertThat(repositoryScore(exam, 2023, SessionType.NORMAL, 10001)).isNull(); // DRAFT
        assertThat(repositoryScore(exam, 2024, SessionType.COMPLEMENTARY, 10001)).isNull();
        assertThat(repositoryScore(exam, 2025, session, 99999)).isNull();
        if (exam == ExamType.C1AS) assertThat(repositoryScore(exam, 2025, session, 90001)).isNull();
    }

    private BigDecimal repositoryScore(ExamType exam, int year, SessionType session, long number) {
        return switch (exam) {
            case BAC -> bac.findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndCandidateNumber(exam,year,session,PublicationStatus.PUBLISHED,number).map(BacResult::getScore).orElse(null);
            case BEPC -> bepc.findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndCandidateNumber(exam,year,session,PublicationStatus.PUBLISHED,number).map(BepcResult::getScore).orElse(null);
            case C1AS -> c1as.findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndDossierNumber(exam,year,session,PublicationStatus.PUBLISHED,number).map(C1asResult::getTotalScore).orElse(null);
        };
    }

    @ParameterizedTest
    @CsvSource({"bac,NORMAL,12.5", "bac,COMPLEMENTARY,14", "bepc,NORMAL,12.5", "bepc,COMPLEMENTARY,14", "c1as,NORMAL,12.5", "c1as,COMPLEMENTARY,14"})
    void publicResultUsesRealJpa(String exam, String session, double score) throws Exception {
        mvc.perform(get(resultUrl(exam,2025,10001)).param("sessionType", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.examType").value(exam.toUpperCase()))
            .andExpect(jsonPath("$.identifier").value("10001")).andExpect(jsonPath("$.year").value(2025))
            .andExpect(jsonPath("$.sessionType").value(session)).andExpect(jsonPath("$.score").value(score));
    }

    private String resultUrl(String exam, int year, long identifier) {
        return "/api/public/results/" + exam + "/" + year + "/" + (exam.equals("c1as") ? "dossier/" : "") + identifier;
    }

    @ParameterizedTest
    @ValueSource(strings={"bac","bepc","c1as"})
    void missingResultsNeverFallBack(String exam) throws Exception {
        for (String path : new String[]{resultUrl(exam,2024,10001),resultUrl(exam,2025,10002)}) {
            mvc.perform(get(path).param("sessionType","COMPLEMENTARY"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value(path)).andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").exists());
        }
        mvc.perform(get(resultUrl(exam,2023,10001))).andExpect(status().isNotFound());
        mvc.perform(get(resultUrl(exam,2025,10001))).andExpect(status().isOk()).andExpect(jsonPath("$.sessionType").value("NORMAL"));
    }

    @ParameterizedTest
    @CsvSource({"BAC,NORMAL,2,10", "BAC,COMPLEMENTARY,1,14", "BEPC,NORMAL,2,10", "BEPC,COMPLEMENTARY,1,14", "C1AS,NORMAL,2,10", "C1AS,COMPLEMENTARY,1,14"})
    void nativeCatalogQueriesKeepSessionsSeparate(String exam, String session, int count, double average) throws Exception {
        catalog("search",exam,2025,session).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(count)).andExpect(jsonPath("$.content[0].identifier").value("10001"))
            .andExpect(jsonPath("$.content[0].sessionType").value(session));
        mvc.perform(get("/api/public/catalog/statistics/"+exam+"/2025").param("sessionType",session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.candidateCount").value(count))
            .andExpect(jsonPath("$.averageScore").value(average)).andExpect(jsonPath("$.sessionType").value(session));
        var response = catalog("institutions",exam,2025,session).andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].candidateCount").value(count)).andReturn().getResponse();
        String id = json.readTree(response.getContentAsString()).at("/content/0/id").asText();
        mvc.perform(get("/api/public/catalog/institutions/{id}",id).param("sessionType",session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.candidateCount").value(count))
            .andExpect(jsonPath("$.summary.averageScore").value(average)).andExpect(jsonPath("$.summary.sessionType").value(session));
    }

    private ResultActions catalog(String endpoint, String exam, int year, String session) throws Exception {
        return mvc.perform(get("/api/public/catalog/"+endpoint).param("examType",exam).param("year",""+year).param("sessionType",session));
    }

    @Test
    void catalogListsOnlyPublishedSessions() throws Exception {
        mvc.perform(get("/api/public/catalog/exams")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/public/catalog/exams/BAC/sessions")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[?(@.year == 2023)]").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings={"BAC","BEPC","C1AS"})
    void searchFiltersAndPagination(String exam) throws Exception {
        mvc.perform(get("/api/public/catalog/search").param("examType",exam).param("year","2025").param("size","1").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].identifier").value("10002"));
        mvc.perform(get("/api/public/catalog/search").param("examType",exam).param("year","2025").param("candidateNumber","10001")
            .param("name","synthetic").param("institution","school").param("wilaya","region"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        catalog("search",exam,2024,"NORMAL").andExpect(jsonPath("$.content[0].score").value(9));
        catalog("search",exam,2024,"COMPLEMENTARY").andExpect(jsonPath("$.totalElements").value(0));
        catalog("search",exam,2023,"NORMAL").andExpect(jsonPath("$.totalElements").value(0));
        catalog("institutions",exam,2024,"COMPLEMENTARY").andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/public/catalog/statistics/"+exam+"/2024").param("sessionType","COMPLEMENTARY"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.candidateCount").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings={"BAC","BEPC","C1AS"})
    void emptyYearHasEmptySearchInstitutionsAndStatistics(String exam) throws Exception {
        catalog("search",exam,1999,"NORMAL").andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        catalog("institutions",exam,1999,"NORMAL").andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/public/catalog/statistics/"+exam+"/1999")).andExpect(status().isOk()).andExpect(jsonPath("$.candidateCount").value(0));
    }

    @Test
    void adminBasicAuthenticationIsReal() throws Exception {
        String path="/api/admin/imports/999999/publish";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(httpBasic("integration-admin","wrong"))).andExpect(status().isUnauthorized());
        // Authenticated request reaches the service; nonexistent synthetic ID prevents any publication.
        mvc.perform(post(path).with(httpBasic("integration-admin","synthetic-test-password")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").isString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM batch_job_execution",Integer.class)).isZero();
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/public/catalog/exams").header("Origin","http://localhost:4200").header("Access-Control-Request-Method","GET"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:4200"));
        mvc.perform(options("/api/public/catalog/exams").header("Origin","https://untrusted.invalid").header("Access-Control-Request-Method","GET"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/public/catalog/search?examType=UNKNOWN&year=2025", "/api/public/results/bac/no-year/10001", "/api/public/results/bac/2025/10001?sessionType=INVALID", "/api/public/catalog/search?examType=BAC&year=2025&sessionType=INVALID"})
    void invalidParametersAreRejected(String url) throws Exception {
        mvc.perform(get(url)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.message").isString());
    }
    @Test
    void institutionPaginationAndQueryUsePostgres() throws Exception {
        jdbc.update("UPDATE bac_result SET institution_fr='Another school' WHERE candidate_number=10002");
        mvc.perform(get("/api/public/catalog/institutions").param("examType","BAC").param("year","2025").param("size","1").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].nameFr").value("Synthetic school"));
        mvc.perform(get("/api/public/catalog/institutions").param("examType","BAC").param("year","2025").param("query","ANOTHER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void examWithNoPublishedDataIsUnavailable() throws Exception {
        jdbc.update("UPDATE exam_session SET status='DRAFT' WHERE exam_type='BEPC'");
        mvc.perform(get("/api/public/catalog/exams"))
            .andExpect(jsonPath("$[?(@.examType == 'BEPC')].available").value(org.hamcrest.Matchers.contains(false)));
        catalog("search","BEPC",2025,"NORMAL").andExpect(jsonPath("$.totalElements").value(0));
        catalog("institutions","BEPC",2025,"NORMAL").andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/public/catalog/statistics/BEPC/2025")).andExpect(jsonPath("$.candidateCount").value(0));
    }

    @ParameterizedTest
    @CsvSource({"bac_result,candidate_number", "bepc_result,candidate_number", "c1as_result,dossier_number"})
    void databaseRejectsDuplicateResultsAndOrphanSessions(String table, String identifier) throws Exception {
        // Savepoints allow checking PostgreSQL constraint failures without poisoning the test transaction.
        var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        var savepoint = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            var duplicate = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                () -> statement.executeUpdate("UPDATE " + table + " SET " + identifier + "=10001 WHERE " + identifier + "=10002"));
            assertThat(duplicate.getSQLState()).isEqualTo("23505");
            connection.rollback(savepoint);
            savepoint = connection.setSavepoint();
            var orphan = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                () -> statement.executeUpdate("UPDATE " + table + " SET exam_session_id=-1 WHERE id=(SELECT min(id) FROM " + table + ")"));
            assertThat(orphan.getSQLState()).isEqualTo("23503");
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

}
