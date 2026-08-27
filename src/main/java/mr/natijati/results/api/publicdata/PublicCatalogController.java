package mr.natijati.results.api.publicdata;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mr.natijati.results.api.publicdata.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/public/catalog")
@Tag(name = "Catalogue public")
public class PublicCatalogController {

    private final PublicCatalogService service;

    public PublicCatalogController(PublicCatalogService service) {
        this.service = service;
    }

    @GetMapping("/exams")
    @Operation(summary = "Lister les examens et sessions publiées")
    public List<ExamAvailabilityResponse> exams() {
        return service.exams();
    }

    @GetMapping("/exams/{examType}/sessions")
    @Operation(summary = "Lister les sessions publiées d'un examen")
    public List<ExamSessionView> sessions(@PathVariable String examType) {
        return service.sessions(examType);
    }

    @GetMapping("/search")
    @Operation(summary = "Recherche publique avancée")
    public PageResponse<SearchResultItem> search(
        @RequestParam String examType,
        @RequestParam Integer year,
        @RequestParam(required = false) String candidateNumber,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) String wilaya,
        @RequestParam(required = false) String series,
        @RequestParam(required = false) String institution,
        @RequestParam(defaultValue = "0") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        return service.search(new SearchCriteria(
            examType,
            year,
            candidateNumber,
            name,
            wilaya,
            series,
            institution,
            page,
            size
        ));
    }

    @GetMapping("/statistics/{examType}/{year}")
    @Operation(summary = "Statistiques publiques d'un examen")
    public StatisticsResponse statistics(
        @PathVariable String examType,
        @PathVariable Integer year
    ) {
        return service.statistics(examType, year);
    }

    @GetMapping("/institutions")
    @Operation(summary = "Lister les établissements")
    public PageResponse<InstitutionSummaryResponse> institutions(
        @RequestParam String examType,
        @RequestParam Integer year,
        @RequestParam(required = false) String query,
        @RequestParam(defaultValue = "0") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        return service.institutions(examType, year, query, page, size);
    }

    @GetMapping("/institutions/{id}")
    @Operation(summary = "Afficher le détail d'un établissement")
    public InstitutionDetailResponse institution(@PathVariable String id) {
        return service.institution(id);
    }
}
