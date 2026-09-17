package mr.natijati.results.api.publicdata;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mr.natijati.results.api.publicdata.dto.*;
import mr.natijati.results.exam.SessionType;
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
    @Operation(summary = "Recherche publique avancée",
        description = "candidateNumber désigne le numéro de dossier pour C1AS, le numéro de candidat pour BAC et BEPC. Sans sessionType, la session NORMAL est sélectionnée.")
    public PageResponse<SearchResultItem> search(
        @RequestParam String examType,
        @RequestParam Integer year,
        @RequestParam(required = false) String candidateNumber,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) String wilaya,
        @RequestParam(required = false) String series,
        @RequestParam(required = false) String institution,
        @RequestParam(defaultValue = "0") Integer page,
        @RequestParam(defaultValue = "20") Integer size,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
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
            size,
            sessionType
        ));
    }

    @GetMapping("/statistics/{examType}/{year}")
    @Operation(summary = "Statistiques publiques d'un examen")
    public StatisticsResponse statistics(
        @PathVariable String examType,
        @PathVariable Integer year,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
    ) {
        return service.statistics(examType, year, sessionType);
    }

    @GetMapping("/institutions")
    @Operation(summary = "Lister les établissements")
    public PageResponse<InstitutionSummaryResponse> institutions(
        @RequestParam String examType,
        @RequestParam Integer year,
        @RequestParam(required = false) String query,
        @RequestParam(defaultValue = "0") Integer page,
        @RequestParam(defaultValue = "20") Integer size,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
    ) {
        return service.institutions(examType, year, query, page, size, sessionType);
    }

    @GetMapping("/institutions/{id}")
    @Operation(summary = "Afficher le détail d'un établissement")
    public InstitutionDetailResponse institution(
        @PathVariable String id,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
    ) {
        return service.institution(id, sessionType);
    }
}
