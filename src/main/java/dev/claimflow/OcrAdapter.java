package dev.claimflow;

/**
 * Seam for an image OCR engine. An adapter only turns image bytes into text; it never decides
 * whether a claim is valid. {@link ReceiptImageIntake} queues the normalized text through the same
 * extraction job, parser, amount, duplicate, audit and manager-correction rules as typed text.
 */
public interface OcrAdapter {
    /** Recognized text plus the engine name recorded in the submission audit detail. */
    record OcrText(String text, String engine) {}

    /**
     * @throws UnreadableReceiptImageException when the engine cannot decode this image (HTTP 400)
     * @throws OcrUnavailableException for an engine outage, timeout or missing configuration (HTTP 503)
     */
    OcrText recognize(ReceiptImageValidator.ReceiptImage image);

    /** The image is well formed enough to accept but the engine cannot read it. */
    class UnreadableReceiptImageException extends IllegalArgumentException {
        public UnreadableReceiptImageException(String message) { super(message); }
    }

    /** A technical OCR failure; no claim is created, so the client may retry with the same key. */
    class OcrUnavailableException extends RuntimeException {
        public OcrUnavailableException(String message) { super(message); }
    }
}
