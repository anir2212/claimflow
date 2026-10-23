package dev.claimflow;

import java.util.Map;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
    private final ClaimMetrics metrics;

    public ApiErrors(ClaimMetrics metrics) { this.metrics = metrics; }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler({ReceiptImageValidator.ReceiptImageTooLargeException.class,
        MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, String>> tooLarge(RuntimeException error) {
        // The validator's own size error is counted by ReceiptImageIntake; this is the servlet limit.
        if (error instanceof MaxUploadSizeExceededException) metrics.upload(ClaimMetrics.UploadOutcome.TOO_LARGE);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(Map.of("error", "Receipt image is too large"));
    }

    @ExceptionHandler(OcrAdapter.OcrUnavailableException.class)
    public ResponseEntity<Map<String, String>> ocrUnavailable(OcrAdapter.OcrUnavailableException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", error.getMessage()));
    }

    /** Optimistic-lock conflicts, lock timeouts and deadlock victims: the caller should reload. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Map<String, String>> concurrentChange(ConcurrencyFailureException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "The claim was changed by another request; reload it and try again"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }
}
