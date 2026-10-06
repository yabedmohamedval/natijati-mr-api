package mr.natijati.results.api.publicdata;

import mr.natijati.results.api.publicdata.dto.*;
import mr.natijati.results.exam.SessionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class PublicReadRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final InstitutionKeyCodec institutionKeyCodec;

    public PublicReadRepository(
        NamedParameterJdbcTemplate jdbc,
        InstitutionKeyCodec institutionKeyCodec
    ) {
        this.jdbc = jdbc;
        this.institutionKeyCodec = institutionKeyCodec;
    }

    public List<ExamSessionView> findPublishedSessions() {
        String sql = """
        SELECT id,
               exam_type,
               exam_year AS year,
               session_type,
               status,
               published_at,
               source_filename,
               result_count
        FROM exam_session
        WHERE status = 'PUBLISHED'
        ORDER BY exam_year DESC, exam_type, session_type
        """;

        return jdbc.query(
                sql,
                new MapSqlParameterSource(),
                (rs, rowNum) -> {
                    Timestamp publishedAt =
                            rs.getTimestamp("published_at");

                    return new ExamSessionView(
                            rs.getLong("id"),
                            rs.getString("exam_type"),
                            rs.getInt("year"),
                            rs.getString("session_type"),
                            rs.getString("status"),
                            publishedAt == null
                                    ? null
                                    : publishedAt.toLocalDateTime(),
                            rs.getString("source_filename"),
                            rs.getLong("result_count")
                    );
                }
        );
    }

    public PageResponse<SearchResultItem> search(SearchCriteria criteria) {
        String examType = normalizeExamType(criteria.examType());
        return switch (examType) {
            case "BAC" -> searchBac(criteria);
            case "BEPC" -> searchBepc(criteria);
            case "C1AS" -> searchC1as(criteria);
            default -> throw new IllegalArgumentException("Type d'examen non supporté: " + examType);
        };
    }

    public StatisticsResponse statistics(String examType, Integer year, SessionType sessionType) {
        String normalized = normalizeExamType(examType);
        return switch (normalized) {
            case "BAC" -> bacStatistics(year, sessionType);
            case "BEPC" -> bepcStatistics(year, sessionType);
            case "C1AS" -> c1asStatistics(year, sessionType);
            default -> throw new IllegalArgumentException("Type d'examen non supporté: " + normalized);
        };
    }

    public PageResponse<InstitutionSummaryResponse> findInstitutions(
        String examType,
        Integer year,
        String query,
        int page,
        int size,
        SessionType sessionType
    ) {
        String normalized = normalizeExamType(examType);
        return switch (normalized) {
            case "BAC" -> bacInstitutions(year, query, page, size, sessionType);
            case "BEPC" -> bepcInstitutions(year, query, page, size, sessionType);
            case "C1AS" -> c1asInstitutions(year, query, page, size, sessionType);
            default -> throw new IllegalArgumentException("Type d'examen non supporté: " + normalized);
        };
    }

    public InstitutionDetailResponse findInstitution(String id, SessionType sessionType) {
        InstitutionKeyCodec.DecodedInstitutionKey key = institutionKeyCodec.decode(id);
        PageResponse<InstitutionSummaryResponse> page = findInstitutions(
            key.examType(),
            key.year(),
            key.institutionName(),
            0,
            10,
            sessionType
        );

        InstitutionSummaryResponse summary = page.content().stream()
            .filter(item -> key.institutionName().equals(item.nameFr())
                || key.institutionName().equals(item.nameAr()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Établissement introuvable"));

        List<DistributionItemResponse> decisions = institutionDecisionDistribution(key, sessionType);
        List<DistributionItemResponse> series = institutionSeriesDistribution(key, sessionType);

        return new InstitutionDetailResponse(summary, decisions, series);
    }

    private PageResponse<SearchResultItem> searchBac(SearchCriteria c) {
        String select = """
            SELECT 'BAC' AS exam_type,
                   es.exam_year AS year,
                   es.session_type,
                   br.candidate_number::text AS identifier,
                   br.name_fr,
                   br.name_ar,
                   br.wilaya_fr,
                   br.wilaya_ar,
                   br.institution_fr,
                   br.institution_ar,
                   br.score,
                   br.decision_code,
                   br.decision_original AS decision_label
            FROM bac_result br
            JOIN exam_session es ON es.id = br.exam_session_id
            WHERE es.status = 'PUBLISHED'
              AND es.exam_type = 'BAC'
              AND es.exam_year = :year
              AND es.session_type = :sessionType
            """;

        QueryParts parts = buildSearchQuery(select, c,
            "br.candidate_number",
            "br.name_fr", "br.name_ar",
            "br.wilaya_fr", "br.wilaya_ar",
            "br.series_code", "br.series_fr", "br.series_ar",
            "br.institution_fr", "br.institution_ar"
        );
        return executeSearch(parts, c);
    }

    private PageResponse<SearchResultItem> searchBepc(SearchCriteria c) {
        String select = """
            SELECT 'BEPC' AS exam_type,
                   es.exam_year AS year,
                   es.session_type,
                   br.candidate_number::text AS identifier,
                   br.name_fr,
                   NULL::text AS name_ar,
                   br.wilaya_fr,
                   NULL::text AS wilaya_ar,
                   br.exam_center_fr AS institution_fr,
                   NULL::text AS institution_ar,
                   br.score,
                   br.decision_code,
                   br.decision_original AS decision_label
            FROM bepc_result br
            JOIN exam_session es ON es.id = br.exam_session_id
            WHERE es.status = 'PUBLISHED'
              AND es.exam_type = 'BEPC'
              AND es.exam_year = :year
              AND es.session_type = :sessionType
            """;

        QueryParts parts = buildSearchQuery(select, c,
            "br.candidate_number",
            "br.name_fr", null,
            "br.wilaya_fr", null,
            null, null, null,
            "br.exam_center_fr", null
        );
        return executeSearch(parts, c);
    }

    private PageResponse<SearchResultItem> searchC1as(SearchCriteria c) {
        String select = """
            SELECT 'C1AS' AS exam_type,
                   es.exam_year AS year,
                   es.session_type,
                   br.dossier_number::text AS identifier,
                   NULL::text AS name_fr,
                   br.name_ar,
                   NULL::text AS wilaya_fr,
                   br.wilaya_ar,
                   NULL::text AS institution_fr,
                   br.school_ar AS institution_ar,
                   br.total_score AS score,
                   NULL::text AS decision_code,
                   NULL::text AS decision_label
            FROM c1as_result br
            JOIN exam_session es ON es.id = br.exam_session_id
            WHERE es.status = 'PUBLISHED'
              AND es.exam_type = 'C1AS'
              AND es.exam_year = :year
              AND es.session_type = :sessionType
            """;

        QueryParts parts = buildSearchQuery(select, c,
            "br.dossier_number",
            null, "br.name_ar",
            null, "br.wilaya_ar",
            null, null, null,
            null, "br.school_ar"
        );
        return executeSearch(parts, c);
    }

    private QueryParts buildSearchQuery(
        String select,
        SearchCriteria c,
        String candidateColumn,
        String nameFrColumn,
        String nameArColumn,
        String wilayaFrColumn,
        String wilayaArColumn,
        String seriesCodeColumn,
        String seriesFrColumn,
        String seriesArColumn,
        String institutionFrColumn,
        String institutionArColumn
    ) {
        StringBuilder sql = new StringBuilder(select);
        MapSqlParameterSource params = new MapSqlParameterSource("year", c.year())
            .addValue("sessionType", c.sessionType().name());

        if (hasText(c.candidateNumber())) {
            sql.append(" AND ").append(candidateColumn).append("::text = :candidateNumber");
            params.addValue("candidateNumber", c.candidateNumber().trim());
        }

        appendTextFilter(sql, params, "name", c.name(), nameFrColumn, nameArColumn);
        appendTextFilter(sql, params, "wilaya", c.wilaya(), wilayaFrColumn, wilayaArColumn);
        appendTextFilter(sql, params, "series", c.series(), seriesCodeColumn, seriesFrColumn, seriesArColumn);
        appendTextFilter(sql, params, "institution", c.institution(), institutionFrColumn, institutionArColumn);

        return new QueryParts(sql.toString(), params);
    }

    private PageResponse<SearchResultItem> executeSearch(QueryParts parts, SearchCriteria c) {
        String countSql = "SELECT COUNT(*) FROM (" + parts.sql() + ") q";
        long total = jdbc.queryForObject(countSql, parts.params(), Long.class);

        parts.params()
            .addValue("limit", c.size())
            .addValue("offset", c.page() * c.size());

        String pageSql = parts.sql() + " ORDER BY score DESC NULLS LAST, identifier LIMIT :limit OFFSET :offset";
        List<SearchResultItem> content = jdbc.query(pageSql, parts.params(), searchResultMapper());
        return PageResponse.of(content, c.page(), c.size(), total);
    }

    private RowMapper<SearchResultItem> searchResultMapper() {
        return (rs, rowNum) -> new SearchResultItem(
            rs.getString("exam_type"),
            rs.getInt("year"),
            rs.getString("identifier"),
            rs.getString("name_fr"),
            rs.getString("name_ar"),
            rs.getString("wilaya_fr"),
            rs.getString("wilaya_ar"),
            rs.getString("institution_fr"),
            rs.getString("institution_ar"),
            rs.getBigDecimal("score"),
            rs.getString("decision_code"),
            rs.getString("decision_label"),
            SessionType.valueOf(rs.getString("session_type"))
        );
    }

    private StatisticsResponse bacStatistics(Integer year, SessionType sessionType) {
        MapSqlParameterSource params = new MapSqlParameterSource("year", year)
            .addValue("sessionType", sessionType.name());
        String summarySql = """
            SELECT COUNT(*) AS candidate_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'ADMITTED') AS admitted_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'FAILED') AS failed_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'SECOND_SESSION') AS second_session_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'CANCELLED') AS cancelled_count,
                   AVG(br.score) AS average_score
            FROM bac_result br
            JOIN exam_session es ON es.id = br.exam_session_id
            WHERE es.status = 'PUBLISHED'
              AND es.exam_type = 'BAC'
              AND es.exam_year = :year
              AND es.session_type = :sessionType
            """;

        return statisticsFromSummary(
            "BAC",
            year,
            sessionType,
            summarySql,
            params,
            distribution("bac_result", "wilaya_fr", "wilaya_fr", "wilaya_ar", year, "BAC", sessionType),
            distribution("bac_result", "series_code", "series_fr", "series_ar", year, "BAC", sessionType)
        );
    }

    private StatisticsResponse bepcStatistics(Integer year, SessionType sessionType) {
        MapSqlParameterSource params = new MapSqlParameterSource("year", year)
            .addValue("sessionType", sessionType.name());
        String summarySql = """
            SELECT COUNT(*) AS candidate_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'ADMITTED') AS admitted_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'FAILED') AS failed_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'SECOND_SESSION') AS second_session_count,
                   COUNT(*) FILTER (WHERE br.decision_code = 'CANCELLED') AS cancelled_count,
                   AVG(br.score) AS average_score
            FROM bepc_result br
            JOIN exam_session es ON es.id = br.exam_session_id
            WHERE es.status = 'PUBLISHED'
              AND es.exam_type = 'BEPC'
              AND es.exam_year = :year
              AND es.session_type = :sessionType
            """;

        return statisticsFromSummary(
            "BEPC",
            year,
            sessionType,
            summarySql,
            params,
            distribution("bepc_result", "wilaya_fr", "wilaya_fr", null, year, "BEPC", sessionType),
            List.of()
        );
    }

    private StatisticsResponse c1asStatistics(Integer year, SessionType sessionType) {
        MapSqlParameterSource params = new MapSqlParameterSource("year", year)
            .addValue("sessionType", sessionType.name());
        String sql = """
            SELECT COUNT(*) AS candidate_count,
                   AVG(br.total_score) AS average_score
            FROM c1as_result br
            JOIN exam_session es ON es.id = br.exam_session_id
            WHERE es.status = 'PUBLISHED'
              AND es.exam_type = 'C1AS'
              AND es.exam_year = :year
              AND es.session_type = :sessionType
            """;

        Map<String, Object> row = jdbc.queryForMap(sql, params);
        long count = number(row.get("candidate_count")).longValue();
        BigDecimal average = decimal(row.get("average_score"));

        return new StatisticsResponse(
            "C1AS",
            year,
            count,
            0,
            0,
            0,
            0,
            average,
            0,
            distribution("c1as_result", "wilaya_ar", null, "wilaya_ar", year, "C1AS", sessionType),
            List.of(),
            sessionType
        );
    }

    private StatisticsResponse statisticsFromSummary(
        String examType,
        Integer year,
        SessionType sessionType,
        String sql,
        MapSqlParameterSource params,
        List<DistributionItemResponse> byWilaya,
        List<DistributionItemResponse> bySeries
    ) {
        Map<String, Object> row = jdbc.queryForMap(sql, params);
        long candidates = number(row.get("candidate_count")).longValue();
        long admitted = number(row.get("admitted_count")).longValue();
        long failed = number(row.get("failed_count")).longValue();
        long second = number(row.get("second_session_count")).longValue();
        long cancelled = number(row.get("cancelled_count")).longValue();
        BigDecimal average = decimal(row.get("average_score"));
        double successRate = candidates == 0 ? 0 : admitted * 100.0 / candidates;

        return new StatisticsResponse(
            examType,
            year,
            candidates,
            admitted,
            failed,
            second,
            cancelled,
            average,
            round2(successRate),
            byWilaya,
            bySeries,
            sessionType
        );
    }

    private List<DistributionItemResponse> distribution(
        String table,
        String codeColumn,
        String labelFrColumn,
        String labelArColumn,
        Integer year,
        String examType,
        SessionType sessionType
    ) {
        String labelFr = labelFrColumn == null ? "NULL::text" : "br." + labelFrColumn;
        String labelAr = labelArColumn == null ? "NULL::text" : "br." + labelArColumn;
        String code = "br." + codeColumn;

        String sql = "SELECT " + code + "::text AS code, " + labelFr + " AS label_fr, " + labelAr + " AS label_ar, COUNT(*) AS item_count "
            + "FROM " + table + " br JOIN exam_session es ON es.id = br.exam_session_id "
            + "WHERE es.status = 'PUBLISHED' AND es.exam_type = :examType AND es.exam_year = :year AND es.session_type = :sessionType "
            + "GROUP BY " + code + ", " + labelFr + ", " + labelAr + " ORDER BY item_count DESC LIMIT 20";

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("examType", examType)
            .addValue("year", year)
            .addValue("sessionType", sessionType.name());

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        long total = rows.stream().mapToLong(row -> number(row.get("item_count")).longValue()).sum();

        return rows.stream().map(row -> {
            long count = number(row.get("item_count")).longValue();
            return new DistributionItemResponse(
                string(row.get("code")),
                string(row.get("label_fr")),
                string(row.get("label_ar")),
                count,
                total == 0 ? 0 : round2(count * 100.0 / total)
            );
        }).toList();
    }

    private PageResponse<InstitutionSummaryResponse> bacInstitutions(Integer year, String query, int page, int size, SessionType sessionType) {
        return institutions(
            "BAC", year, query, page, size, sessionType,
            "bac_result", "institution_fr", "institution_ar", "wilaya_fr", "wilaya_ar", "score", "decision_code"
        );
    }

    private PageResponse<InstitutionSummaryResponse> bepcInstitutions(Integer year, String query, int page, int size, SessionType sessionType) {
        return institutions(
            "BEPC", year, query, page, size, sessionType,
            "bepc_result", "exam_center_fr", null, "wilaya_fr", null, "score", "decision_code"
        );
    }

    private PageResponse<InstitutionSummaryResponse> c1asInstitutions(Integer year, String query, int page, int size, SessionType sessionType) {
        return institutions(
            "C1AS", year, query, page, size, sessionType,
            "c1as_result", null, "school_ar", null, "wilaya_ar", "total_score", null
        );
    }

    private PageResponse<InstitutionSummaryResponse> institutions(
        String examType,
        Integer year,
        String query,
        int page,
        int size,
        SessionType sessionType,
        String table,
        String nameFrColumn,
        String nameArColumn,
        String wilayaFrColumn,
        String wilayaArColumn,
        String scoreColumn,
        String decisionColumn
    ) {
        String nameFr = nameFrColumn == null ? "NULL::text" : "br." + nameFrColumn;
        String nameAr = nameArColumn == null ? "NULL::text" : "br." + nameArColumn;
        String wilayaFr = wilayaFrColumn == null ? "NULL::text" : "MAX(br." + wilayaFrColumn + ")";
        String wilayaAr = wilayaArColumn == null ? "NULL::text" : "MAX(br." + wilayaArColumn + ")";
        String admitted = decisionColumn == null
            ? "0"
            : "COUNT(*) FILTER (WHERE br." + decisionColumn + " = 'ADMITTED')";

        String groupBy = nameFrColumn != null && nameArColumn != null
            ? "br." + nameFrColumn + ", br." + nameArColumn
            : nameFrColumn != null ? "br." + nameFrColumn : "br." + nameArColumn;

        StringBuilder base = new StringBuilder("SELECT " + nameFr + " AS name_fr, " + nameAr + " AS name_ar, "
            + wilayaFr + " AS wilaya_fr, " + wilayaAr + " AS wilaya_ar, "
            + "COUNT(*) AS candidate_count, " + admitted + " AS admitted_count, AVG(br." + scoreColumn + ") AS average_score "
            + "FROM " + table + " br JOIN exam_session es ON es.id = br.exam_session_id "
            + "WHERE es.status = 'PUBLISHED' AND es.exam_type = :examType AND es.exam_year = :year AND es.session_type = :sessionType");

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("examType", examType)
            .addValue("year", year)
            .addValue("sessionType", sessionType.name());

        if (hasText(query)) {
            List<String> filters = new ArrayList<>();
            if (nameFrColumn != null) filters.add("LOWER(COALESCE(br." + nameFrColumn + ", '')) LIKE :query");
            if (nameArColumn != null) filters.add("LOWER(COALESCE(br." + nameArColumn + ", '')) LIKE :query");
            base.append(" AND (").append(String.join(" OR ", filters)).append(")");
            params.addValue("query", "%" + query.trim().toLowerCase(Locale.ROOT) + "%");
        }

        base.append(" GROUP BY ").append(groupBy);
        String countSql = "SELECT COUNT(*) FROM (" + base + ") institutions";
        long total = jdbc.queryForObject(countSql, params, Long.class);

        params.addValue("limit", size).addValue("offset", page * size);
        String pageSql = base + " ORDER BY candidate_count DESC, name_fr NULLS LAST, name_ar NULLS LAST LIMIT :limit OFFSET :offset";

        List<InstitutionSummaryResponse> content = jdbc.query(pageSql, params, (rs, rowNum) -> {
            String nameFrValue = rs.getString("name_fr");
            String nameArValue = rs.getString("name_ar");
            String keyName = nameFrValue != null ? nameFrValue : nameArValue;
            long candidateCount = rs.getLong("candidate_count");
            long admittedCount = rs.getLong("admitted_count");
            double successRate = candidateCount == 0 ? 0 : admittedCount * 100.0 / candidateCount;

            return new InstitutionSummaryResponse(
                institutionKeyCodec.encode(examType, year, keyName),
                examType,
                year,
                nameFrValue,
                nameArValue,
                rs.getString("wilaya_fr"),
                rs.getString("wilaya_ar"),
                candidateCount,
                admittedCount,
                rs.getBigDecimal("average_score"),
                round2(successRate),
                sessionType
            );
        });

        return PageResponse.of(content, page, size, total);
    }

    private List<DistributionItemResponse> institutionDecisionDistribution(
        InstitutionKeyCodec.DecodedInstitutionKey key,
        SessionType sessionType
    ) {
        if ("C1AS".equals(key.examType())) {
            return List.of();
        }

        String table = "BAC".equals(key.examType()) ? "bac_result" : "bepc_result";
        String institutionColumn = "BAC".equals(key.examType()) ? "institution_fr" : "exam_center_fr";

        String sql = "SELECT COALESCE(br.decision_code, 'UNKNOWN') AS code, COUNT(*) AS item_count "
            + "FROM " + table + " br JOIN exam_session es ON es.id = br.exam_session_id "
            + "WHERE es.status = 'PUBLISHED' AND es.exam_type = :examType AND es.exam_year = :year AND es.session_type = :sessionType "
            + "AND br." + institutionColumn + " = :institution GROUP BY br.decision_code ORDER BY item_count DESC";

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("examType", key.examType())
            .addValue("year", key.year())
            .addValue("sessionType", sessionType.name())
            .addValue("institution", key.institutionName());

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        long total = rows.stream().mapToLong(row -> number(row.get("item_count")).longValue()).sum();

        return rows.stream().map(row -> {
            String code = string(row.get("code"));
            long count = number(row.get("item_count")).longValue();
            return new DistributionItemResponse(
                code,
                decisionFr(code),
                decisionAr(code),
                count,
                total == 0 ? 0 : round2(count * 100.0 / total)
            );
        }).toList();
    }

    private List<DistributionItemResponse> institutionSeriesDistribution(
        InstitutionKeyCodec.DecodedInstitutionKey key,
        SessionType sessionType
    ) {
        if (!"BAC".equals(key.examType())) {
            return List.of();
        }

        String sql = """
            SELECT br.series_code AS code,
                   MAX(br.series_fr) AS label_fr,
                   MAX(br.series_ar) AS label_ar,
                   COUNT(*) AS item_count
            FROM bac_result br
            JOIN exam_session es ON es.id = br.exam_session_id
            WHERE es.status = 'PUBLISHED'
              AND es.exam_type = 'BAC'
              AND es.exam_year = :year
              AND es.session_type = :sessionType
              AND br.institution_fr = :institution
            GROUP BY br.series_code
            ORDER BY item_count DESC
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("year", key.year())
            .addValue("sessionType", sessionType.name())
            .addValue("institution", key.institutionName());

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        long total = rows.stream().mapToLong(row -> number(row.get("item_count")).longValue()).sum();

        return rows.stream().map(row -> {
            long count = number(row.get("item_count")).longValue();
            return new DistributionItemResponse(
                string(row.get("code")),
                string(row.get("label_fr")),
                string(row.get("label_ar")),
                count,
                total == 0 ? 0 : round2(count * 100.0 / total)
            );
        }).toList();
    }

    private void appendTextFilter(
        StringBuilder sql,
        MapSqlParameterSource params,
        String parameterName,
        String value,
        String... columns
    ) {
        if (!hasText(value)) return;

        List<String> conditions = new ArrayList<>();
        for (String column : columns) {
            if (column != null) {
                conditions.add("LOWER(COALESCE(" + column + ", '')) LIKE :" + parameterName);
            }
        }
        if (conditions.isEmpty()) return;

        sql.append(" AND (").append(String.join(" OR ", conditions)).append(")");
        params.addValue(parameterName, "%" + value.trim().toLowerCase(Locale.ROOT) + "%");
    }

    private String normalizeExamType(String examType) {
        if (!hasText(examType)) {
            throw new IllegalArgumentException("Le type d'examen est obligatoire");
        }
        return examType.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : null;
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String decisionFr(String code) {
        return switch (code) {
            case "ADMITTED" -> "Admis";
            case "FAILED" -> "Ajourné";
            case "SECOND_SESSION" -> "Sessionnaire";
            case "CANCELLED" -> "Annulation de l'examen";
            default -> "Non renseigné";
        };
    }

    private String decisionAr(String code) {
        return switch (code) {
            case "ADMITTED" -> "ناجح";
            case "FAILED" -> "غير ناجح";
            case "SECOND_SESSION" -> "الدورة التكميلية";
            case "CANCELLED" -> "إلغاء الامتحان";
            default -> "غير محدد";
        };
    }

    private record QueryParts(String sql, MapSqlParameterSource params) {
    }
}
