package dev.claimflow;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Image receipt path: validate bytes, replay a repeated key without calling OCR, call the adapter
 * outside any database transaction, then queue normalized text as an ordinary async extraction job.
 * The scheduled worker applies the existing parser, amount, duplicate and audit rules; invalid text
 * goes to the manager correction queue. Image bytes are never stored.
 */
@Component
public class ReceiptImageIntake {
    private final ReceiptImageValidator validator;
    private final OcrAdapter ocr;
    private final ClaimService service;
    private final ClaimMetrics metrics;

    public ReceiptImageIntake(ReceiptImageValidator validator, OcrAdapter ocr, ClaimService service,
                              ClaimMetrics metrics) {
        this.validator = validator;
        this.ocr = ocr;
        this.service = service;
        this.metrics = metrics;
    }

    /** What a client needs to validate an upload before sending it. */
    public record Limits(long maxBytes, java.util.List<String> mediaTypes, boolean ocrConfigured) {}

    public Limits limits() {
        return new Limits(validator.maxBytes(), java.util.List.of("image/png", "image/jpeg"), ocr.configured());
    }

    public Claim submit(String employee, String key, String category, long amountCents,
                        byte[] bytes, String declaredType) {
        try {
            Intake result = intake(employee, key, category, amountCents, bytes, declaredType);
            metrics.upload(result.replayed() ? ClaimMetrics.UploadOutcome.REPLAYED : ClaimMetrics.UploadOutcome.QUEUED);
            return result.claim();
        } catch (ReceiptImageValidator.ReceiptImageTooLargeException tooLarge) {
            metrics.upload(ClaimMetrics.UploadOutcome.TOO_LARGE);
            throw tooLarge;
        } catch (OcrAdapter.UnreadableReceiptImageException unreadable) {
            metrics.upload(ClaimMetrics.UploadOutcome.UNREADABLE);
            throw unreadable;
        } catch (OcrAdapter.OcrUnavailableException unavailable) {
            metrics.upload(ClaimMetrics.UploadOutcome.OCR_UNAVAILABLE);
            throw unavailable;
        } catch (IllegalArgumentException invalid) {
            metrics.upload(ClaimMetrics.UploadOutcome.INVALID);
            throw invalid;
        }
    }

    private record Intake(Claim claim, boolean replayed) {}

    private Intake intake(String employee, String key, String category, long amountCents,
                          byte[] bytes, String declaredType) {
        ReceiptImageValidator.ReceiptImage image = validator.validate(bytes, declaredType);
        Claim prior = service.replayAsync(employee, key, category, amountCents, image.sha256());
        if (prior != null) return new Intake(prior, true);
        OcrAdapter.OcrText recognized = ocr.recognize(image);
        String text = ReceiptImageValidator.normalizeText(recognized == null ? null : recognized.text());
        if (text.isEmpty()) {
            throw new OcrAdapter.UnreadableReceiptImageException("No text was recognized in the receipt image");
        }
        ClaimService.Submit input = new ClaimService.Submit(category, amountCents, text);
        try {
            return new Intake(service.submitOcrAsync(employee, key, input, image.sha256(), recognized.engine()), false);
        } catch (DataIntegrityViolationException collision) {
            Claim winner = service.replayAsync(employee, key, category, amountCents, image.sha256());
            if (winner != null) return new Intake(winner, true);
            throw collision;
        }
    }
}
