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

    private final ImportStorageService storageService;
    private final ExamSessionService sessionService;
    private final JobLauncher jobLauncher;
    private final Job examResultImportJob;

    public ImportController(
        ImportStorageService storageService,
        ExamSessionService sessionService,
        JobLauncher jobLauncher,
        Job examResultImportJob
    ) {
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
        Path storedFile = storageService.store(file);

        ExamSession session = sessionService.prepareSession(
            examType,
            year,
            sessionType,
            file.getOriginalFilename()
        );

        JobParameters parameters = new JobParametersBuilder()
            .addString("filePath", storedFile.toString())
            .addString("examType", examType.name())
            .addLong("sessionId", session.getId())
            .addLong("requestedAt", System.currentTimeMillis())
            .toJobParameters();

        JobExecution execution = jobLauncher.run(
            examResultImportJob,
            parameters
        );

        ExamSession updatedSession = sessionService.getRequired(session.getId());

        return new ImportJobResponse(
            session.getId(),
            execution.getId(),
            execution.getStatus().name(),
            updatedSession.getStatus().name(),
            updatedSession.getResultCount()
        );
    }

    @PostMapping("/{sessionId}/publish")
    @Operation(summary = "Publier une session importée et validée")
    public ExamSessionResponse publish(@PathVariable Long sessionId) {
        return ExamSessionResponse.from(sessionService.publish(sessionId));
    }
}
