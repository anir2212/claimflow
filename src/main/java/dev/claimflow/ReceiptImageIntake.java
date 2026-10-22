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

    public ReceiptImageIntake(ReceiptImageValidator validator, OcrAdapter ocr, ClaimService service) {
        this.validator = validator;
        this.ocr = ocr;
        this.service = service;
    }

    public Claim submit(String employee, String key, String category, long amountCents,
                        byte[] bytes, String declaredType) {
        ReceiptImageValidator.ReceiptImage image = validator.validate(bytes, declaredType);
        Claim prior = service.replayAsync(employee, key, category, amountCents, image.sha256());
        if (prior != null) return prior;
        OcrAdapter.OcrText recognized = ocr.recognize(image);
        String text = ReceiptImageValidator.normalizeText(recognized == null ? null : recognized.text());
        if (text.isEmpty()) {
            throw new OcrAdapter.UnreadableReceiptImageException("No text was recognized in the receipt image");
        }
        ClaimService.Submit input = new ClaimService.Submit(category, amountCents, text);
        try {
            return service.submitOcrAsync(employee, key, input, image.sha256(), recognized.engine());
        } catch (DataIntegrityViolationException collision) {
            Claim winner = service.replayAsync(employee, key, category, amountCents, image.sha256());
            if (winner != null) return winner;
            throw collision;
        }
    }
}
