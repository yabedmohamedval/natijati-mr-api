package mr.natijati.results.importing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mr.natijati.results.exam.*;
import mr.natijati.results.importing.async.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/admin/imports")
@Tag(name = "Administration des imports")
public class ImportController {
    private final ImportSubmissionService submissions;
    private final ImportExecutionRepository executions;
    private final ExamSessionService sessions;
    public ImportController(ImportSubmissionService submissions, ImportExecutionRepository executions, ExamSessionService sessions) {
        this.submissions=submissions;this.executions=executions;this.sessions=sessions;
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary="Soumettre un import durable ; traitement asynchrone")
    public ResponseEntity<ImportExecutionResponse> importResults(@RequestPart("file") MultipartFile file,
        @RequestParam ExamType examType, @RequestParam int year,
        @RequestParam(defaultValue="NORMAL") SessionType sessionType) throws java.io.IOException {
        var response=submissions.submit(file,examType,year,sessionType);
        return ResponseEntity.accepted().location(URI.create("/api/admin/imports/"+response.importId())).body(response);
    }
    @GetMapping("/{importId}")
    public ImportExecutionResponse status(@PathVariable UUID importId) { return ImportExecutionResponse.from(executions.get(importId)); }
    @GetMapping
    public List<ImportExecutionResponse> recent(@RequestParam(defaultValue="20") int limit) { return executions.recent(limit); }
    @PostMapping("/{sessionId}/publish")
    @Operation(summary="Publier une session importée et validée")
    public ExamSessionResponse publish(@PathVariable Long sessionId) { return ExamSessionResponse.from(sessions.publish(sessionId)); }
}
