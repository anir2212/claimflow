package dev.claimflow;

import org.springframework.stereotype.Component;

/** Default adapter: no OCR engine is bundled, so image submissions return HTTP 503. */
@Component
public class UnconfiguredOcrAdapter implements OcrAdapter {
    @Override
    public OcrText recognize(ReceiptImageValidator.ReceiptImage image) {
        throw new OcrUnavailableException("Image OCR is not configured on this server");
    }

    @Override
    public boolean configured() { return false; }
}
