package mr.natijati.results.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResultNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
        ResultNotFoundException exception,
        HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, exception, request);
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        IllegalStateException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(
        RuntimeException exception,
        HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_REQUEST, exception, request);
    }

    private ResponseEntity<ApiError> build(
        HttpStatus status,
        Exception exception,
        HttpServletRequest request
    ) {
        ApiError error = new ApiError(
            OffsetDateTime.now(),
            status.value(),
            status.getReasonPhrase(),
            exception.getMessage(),
            request.getRequestURI()
        );

        return ResponseEntity.status(status).body(error);
    }
}
