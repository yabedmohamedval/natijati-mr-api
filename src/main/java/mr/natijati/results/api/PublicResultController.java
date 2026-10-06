package mr.natijati.results.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mr.natijati.results.exam.ExamType;
import mr.natijati.results.exam.PublicationStatus;
import mr.natijati.results.exam.SessionType;
import mr.natijati.results.result.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/results")
@Tag(name = "Consultation publique des résultats")
public class PublicResultController {

    private static final Logger log = LoggerFactory.getLogger(PublicResultController.class);
    private final BacResultRepository bacResultRepository;
    private final BepcResultRepository bepcResultRepository;
    private final C1asResultRepository c1asResultRepository;

    public PublicResultController(
        BacResultRepository bacResultRepository,
        BepcResultRepository bepcResultRepository,
        C1asResultRepository c1asResultRepository
    ) {
        this.bacResultRepository = bacResultRepository;
        this.bepcResultRepository = bepcResultRepository;
        this.c1asResultRepository = c1asResultRepository;
    }

    @GetMapping("/bac/{year}/{candidateNumber}")
    @Operation(summary = "Chercher un résultat BAC par numéro")
    public PublicResultResponse findBac(
        @PathVariable Integer year,
        @PathVariable Long candidateNumber,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
    ) {
        BacResult result = bacResultRepository
            .findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndCandidateNumber(
                ExamType.BAC,
                year,
                sessionType,
                PublicationStatus.PUBLISHED,
                candidateNumber
            )
            .orElseThrow(() -> new ResultNotFoundException("Résultat BAC introuvable"));

        return new PublicResultResponse(
            "BAC",
            year,
            result.getCandidateNumber().toString(),
            result.getNameFr(),
            result.getNameAr(),
            result.getWilayaFr(),
            result.getWilayaAr(),
            result.getInstitutionFr(),
            result.getInstitutionAr(),
            result.getExamCenterFr(),
            result.getExamCenterAr(),
            result.getSeriesCode(),
            result.getSeriesFr(),
            result.getSeriesAr(),
            result.getScore(),
            "Moyenne",
            result.getDecisionCode() == null ? null : result.getDecisionCode().name(),
            result.getDecisionOriginal(),
            sessionType
        );
    }

    @GetMapping("/bepc/{year}/{candidateNumber}")
    @Operation(summary = "Chercher un résultat BEPC par numéro")
    public PublicResultResponse findBepc(
        @PathVariable Integer year,
        @PathVariable Long candidateNumber,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
    ) {
        BepcResult result = bepcResultRepository
            .findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndCandidateNumber(
                ExamType.BEPC,
                year,
                sessionType,
                PublicationStatus.PUBLISHED,
                candidateNumber
            )
            .orElseThrow(() -> new ResultNotFoundException("Résultat BEPC introuvable"));

        return new PublicResultResponse(
            "BEPC",
            year,
            result.getCandidateNumber().toString(),
            result.getNameFr(),
            null,
            result.getWilayaFr(),
            null,
            null,
            null,
            result.getExamCenterFr(),
            null,
            null,
            null,
            null,
            result.getScore(),
            "Moyenne",
            result.getDecisionCode() == null ? null : result.getDecisionCode().name(),
            result.getDecisionOriginal(),
            sessionType
        );
    }

    @GetMapping("/c1as/{year}/dossier/{dossierNumber}")
    @Operation(summary = "Chercher un résultat C1AS par numéro de dossier")
    public PublicResultResponse findC1as(
        @PathVariable Integer year,
        @PathVariable Long dossierNumber,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
    ) {
        C1asResult result = c1asResultRepository
            .findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndDossierNumber(
                ExamType.C1AS,
                year,
                sessionType,
                PublicationStatus.PUBLISHED,
                dossierNumber
            )
            .orElseThrow(() -> new ResultNotFoundException("Résultat C1AS introuvable"));

        return new PublicResultResponse(
            "C1AS",
            year,
            result.getDossierNumber().toString(),
            null,
            result.getNameAr(),
            null,
            result.getWilayaAr(),
            null,
            result.getSchoolAr(),
            null,
            result.getExamCenterAr(),
            null,
            null,
            null,
            result.getTotalScore(),
            "Total",
            null,
            null,
            sessionType
        );
    }
}
