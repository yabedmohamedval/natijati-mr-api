package mr.natijati.results.importing;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.annotation.Order;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice(assignableTypes = ImportController.class)
@Order(-1)
public class ImportExceptionHandler {
    @ExceptionHandler(InvalidExamRowException.class)
    ResponseEntity<ValidationError> invalid(InvalidExamRowException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ValidationError(OffsetDateTime.now(),400,"Bad Request",ex.getMessage(),request.getRequestURI(),List.of(ex.getIssue())));
    }
    record ValidationError(OffsetDateTime timestamp, int status, String error, String message, String path, List<ImportIssue> errors) { }
}
