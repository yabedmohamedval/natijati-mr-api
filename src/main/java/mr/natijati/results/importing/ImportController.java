package mr.natijati.results.importing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mr.natijati.results.exam.*;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/admin/imports")
@Tag(name = "Administration des imports")
public class ImportController {

    // Bound concurrent workbook memory within one JVM. Other instances use the DB reservation.
    private final java.util.concurrent.Semaphore importSlot = new java.util.concurrent.Semaphore(1);
    private final ImportPreflight preflight;
    private final ImportStorageService storageService;
    private final ExamSessionService sessionService;
    private final JobLauncher jobLauncher;
    private final Job examResultImportJob;

    public ImportController(
        ImportPreflight preflight,
        ImportStorageService storageService,
        ExamSessionService sessionService,
        JobLauncher jobLauncher,
        Job examResultImportJob
    ) {
        this.preflight = preflight;
        this.storageService = storageService;
        this.sessionService = sessionService;
        this.jobLauncher = jobLauncher;
        this.examResultImportJob = examResultImportJob;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importer un fichier officiel de résultats")
    public ImportJobResponse importResults(
        @RequestPart("file") MultipartFile file,
        @RequestParam ExamType examType,
        @RequestParam int year,
        @RequestParam(defaultValue = "NORMAL") SessionType sessionType
    ) throws Exception {
        if (year < 1900 || year > 2100) throw new IllegalArgumentException("Année hors plage 1900–2100");
        long started = System.nanoTime();
        if (!importSlot.tryAcquire()) throw new IllegalStateException("Un import est déjà actif sur cette instance ; réessayez après sa fin");
        Path storedFile = null;
        ExamSession session = null;
        try {
            storedFile = storageService.store(file);
            var validation = preflight.inspect(storedFile, examType);
            long expectedRows = validation.rows();
            session = sessionService.prepareSession(examType, year, sessionType, storedFile.getFileName().toString());
            JobParameters parameters = new JobParametersBuilder()
                .addString("filePath", storedFile.toString()).addString("examType", examType.name())
                .addLong("sessionId", session.getId()).addLong("expectedRows", expectedRows)
                .addString("attempt", java.util.UUID.randomUUID().toString()).toJobParameters();
            JobExecution execution = jobLauncher.run(examResultImportJob, parameters);
            ExamSession updated = sessionService.getRequired(session.getId());
            java.util.List<ImportIssue> errors = new java.util.ArrayList<>();
            for (Throwable failure : execution.getAllFailureExceptions()) {
                if (failure instanceof InvalidExamRowException invalid) { errors.add(invalid.getIssue()); break; }
            }
            if (execution.getStatus() != BatchStatus.COMPLETED && errors.isEmpty())
                errors.add(new ImportIssue(examType.name(), 0, "BATCH", "[masquée]", "Échec technique ; consulter jobExecutionId"));
            return new ImportJobResponse(session.getId(), execution.getId(), execution.getStatus().name(), updated.getStatus().name(),
                updated.getResultCount(), execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum(),
                errors.stream().filter(e -> e.rowNumber() > 0).count(), (System.nanoTime()-started)/1_000_000, java.util.List.copyOf(errors), validation.ignoredColumns());
        } catch (Exception ex) {
            if (session != null && sessionService.getRequired(session.getId()).getStatus() == PublicationStatus.IMPORTING)
                sessionService.markFailed(session.getId());
            throw ex;
        } finally {
            try { storageService.delete(storedFile); }
            finally { importSlot.release(); }
        }
    }

    @PostMapping("/{sessionId}/publish")
    @Operation(summary = "Publier une session importée et validée")
    public ExamSessionResponse publish(@PathVariable Long sessionId) {
        return ExamSessionResponse.from(sessionService.publish(sessionId));
    }
}
