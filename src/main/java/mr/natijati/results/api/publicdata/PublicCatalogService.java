package mr.natijati.results.api.publicdata;

import mr.natijati.results.api.publicdata.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PublicCatalogService {

    private static final List<String> EXAM_TYPES = List.of("BAC", "BEPC", "C1AS");

    private final PublicReadRepository repository;

    public PublicCatalogService(PublicReadRepository repository) {
        this.repository = repository;
    }

    public List<ExamAvailabilityResponse> exams() {
        Map<String, List<ExamSessionView>> sessionsByExam = repository
            .findPublishedSessions()
            .stream()
            .collect(Collectors.groupingBy(ExamSessionView::examType));

        return EXAM_TYPES.stream()
            .map(examType -> {
                List<ExamSessionView> sessions = sessionsByExam.getOrDefault(examType, List.of());
                return new ExamAvailabilityResponse(examType, !sessions.isEmpty(), sessions);
            })
            .toList();
    }

    public List<ExamSessionView> sessions(String examType) {
        String normalized = examType.toUpperCase();
        return repository.findPublishedSessions().stream()
            .filter(session -> normalized.equals(session.examType()))
            .toList();
    }

    public PageResponse<SearchResultItem> search(SearchCriteria criteria) {
        if (criteria.year() == null) {
            throw new IllegalArgumentException("L'année est obligatoire");
        }
        return repository.search(criteria);
    }

    public StatisticsResponse statistics(String examType, Integer year) {
        return repository.statistics(examType, year);
    }

    public PageResponse<InstitutionSummaryResponse> institutions(
        String examType,
        Integer year,
        String query,
        Integer page,
        Integer size
    ) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null ? 20 : Math.min(Math.max(size, 1), 50);
        return repository.findInstitutions(examType, year, query, safePage, safeSize);
    }

    public InstitutionDetailResponse institution(String id) {
        return repository.findInstitution(id);
    }
}
