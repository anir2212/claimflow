package dev.claimflow.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.claimflow.OcrAdapter;
import dev.claimflow.ReceiptImageValidator;
import dev.claimflow.ReceiptParser;

/**
 * Runs one manifest split through the production image path: byte validation, OCR adapter, text
 * normalization, {@link ReceiptParser} and the claim-amount rule. The employee's claimed amount is
 * taken to be the labelled total, as for an honest claim. Duplicate detection is not simulated
 * because each benchmark receipt is scored independently.
 */
public final class ReceiptBenchmark {
    private final ReceiptImageValidator validator;
    private final OcrAdapter ocr;
    private final ReceiptParser parser;

    public ReceiptBenchmark(ReceiptImageValidator validator, OcrAdapter ocr, ReceiptParser parser) {
        this.validator = validator;
        this.ocr = ocr;
        this.parser = parser;
    }

    public List<Prediction> predict(ReceiptManifest manifest, String split, Path imageRoot) {
        if (!ManifestValidator.SPLITS.contains(split)) throw new IllegalArgumentException("Unknown split: " + split);
        Path root = imageRoot.toAbsolutePath().normalize();
        List<Prediction> predictions = new ArrayList<>();
        for (ReceiptManifest.Entry entry : manifest.split(split)) {
            Path image = root.resolve(entry.imagePath()).normalize();
            if (!image.startsWith(root)) throw new IllegalArgumentException("Image path escapes root: " + entry.imagePath());
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(image);
            } catch (IOException missing) {
                throw new UncheckedIOException("Cannot read " + entry.imagePath(), missing);
            }
            predictions.add(predictOne(entry, bytes));
        }
        return predictions;
    }

    Prediction predictOne(ReceiptManifest.Entry entry, byte[] bytes) {
        String id = entry.receiptId();
        String text;
        try {
            OcrAdapter.OcrText recognized = ocr.recognize(validator.validate(bytes, null));
            text = ReceiptImageValidator.normalizeText(recognized == null ? null : recognized.text());
        } catch (OcrAdapter.OcrUnavailableException unavailable) {
            return new Prediction(id, Prediction.Outcome.OCR_UNAVAILABLE, null, null, null, null);
        } catch (IllegalArgumentException rejected) {
            return new Prediction(id, Prediction.Outcome.REJECTED, null, null, null, null);
        }
        if (text.isEmpty() || text.length() > 10000) {
            return new Prediction(id, Prediction.Outcome.REJECTED, null, null, null, null);
        }
        ReceiptParser.Parsed parsed;
        try {
            parsed = parser.parse(text);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return new Prediction(id, Prediction.Outcome.NEEDS_HUMAN_CHECK, null, null, null, null);
        }
        Prediction.Outcome outcome = parsed.cents() == entry.totalCentsValue()
            ? Prediction.Outcome.EXTRACTED : Prediction.Outcome.NEEDS_HUMAN_CHECK;
        return new Prediction(id, outcome, parsed.merchant(), parsed.date().toString(), parsed.cents(), "SGD");
    }
}
