package dev.claimflow.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import dev.claimflow.OcrAdapter;
import dev.claimflow.ReceiptImageValidator;
import dev.claimflow.ReceiptParser;
import dev.claimflow.ScriptedOcrAdapter;
import dev.claimflow.SyntheticReceiptImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Harness tests on SYNTHETIC fixtures. The counts asserted here are hand-computed, not OCR metrics. */
class ReceiptBenchmarkTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    @TempDir Path images;

    private static String fixture() throws Exception {
        try (var input = ReceiptBenchmarkTest.class.getResourceAsStream("/receipt_benchmark/synthetic_manifest.csv")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String header() { return String.join(",", ReceiptManifest.HEADER) + "\n"; }

    private static String row(String id, String path, String merchant, String split, String group) {
        return id + "," + path + "," + merchant + ",2026-09-01,1000,SGD," + split + "," + group
            + ",synthetic-fixture-generator,synthetic-fixture:no-real-receipt,true,NOT_REQUIRED,2099-12-31\n";
    }

    private void writeImages(ReceiptManifest manifest) throws Exception {
        for (ReceiptManifest.Entry e : manifest.entries()) {
            Path file = images.resolve(e.imagePath());
            Files.createDirectories(file.getParent());
            Files.write(file, e.imagePath().endsWith(".jpg")
                ? SyntheticReceiptImages.jpeg(e.receiptId()) : SyntheticReceiptImages.png(e.receiptId()));
        }
    }

    @Test
    void syntheticFixtureManifestIsValidAndGroupedBySplit() throws Exception {
        ReceiptManifest manifest = ReceiptManifest.parse(fixture());
        assertEquals(10, manifest.entries().size());
        assertEquals(List.of(), ManifestValidator.validate(manifest, TODAY, null));
        assertEquals("Synthetic Florist, Level 2", manifest.split("test").get(1).merchant());
        assertEquals(List.of("row 2 (syn-train-001): image file not found"),
            ManifestValidator.validate(manifest, TODAY, images).subList(0, 1));
        writeImages(manifest);
        assertEquals(List.of(), ManifestValidator.validate(manifest, TODAY, images));
        assertTrue(manifest.entries().stream().allMatch(ReceiptManifest.Entry::isSynthetic));
    }

    @Test
    void validatorRejectsLeakageUnsafeProvenanceAndBadLabels() {
        String csv = header()
            + row("a1", "img/a1.png", "Shared Cafe", "train", "g-shared")
            + row("a2", "img/a2.png", "Other Cafe", "test", "g-shared")
            + row("a3", "img/a3.png", "shared  cafe.", "development", "g-three")
            + row("a1", "../a4.png", "", "holdout", "")
            + "b1,img/b1.gif,Bad Labels,2026-02-30,0,sgd,train,g-b,,,maybe,PENDING,2026-10-19\n"
            + "c1,/abs/c1.png,Real Shop,2026-09-01,100,SGD,test,g-c,consented-volunteer,consent-ref-17,false,REDACTED,2099-01-01\n";
        List<String> errors = ManifestValidator.validate(ReceiptManifest.parse(csv), TODAY, null);
        String all = String.join("\n", errors);
        for (String expected : List.of(
            "group_id 'g-shared' appears in both train and test splits",
            "merchant 'shared  cafe.' appears in both train and development splits",
            "row 5 (a1): duplicate receipt_id",
            "row 5 (a1): image_path must be a relative",
            "row 5 (a1): merchant must be 1 to 200 characters",
            "row 5 (a1): split must be train, development or test",
            "row 5 (a1): group_id is required",
            "row 6 (b1): image_path must be a relative",
            "row 6 (b1): date must be ISO yyyy-MM-dd",
            "row 6 (b1): total_cents must be a positive integer",
            "row 6 (b1): currency must be a 3-letter ISO 4217 code",
            "row 6 (b1): source is required",
            "row 6 (b1): authorization reference is required",
            "row 6 (b1): synthetic must be true or false",
            "row 6 (b1): redaction must be REDACTED or NOT_REQUIRED",
            "row 6 (b1): retention expired on 2026-10-19",
            "row 7 (c1): image_path must be a relative",
            "test split mixes synthetic and real receipts")) {
            assertTrue(all.contains(expected), "missing: " + expected + "\n" + all);
        }
        assertEquals(18, errors.size(), all);
        assertThrows(IllegalArgumentException.class, () -> ReceiptManifest.parse("receipt_id,image_path\n"));
        assertThrows(IllegalArgumentException.class, () -> ReceiptManifest.parse(header() + "x,\"unterminated\n"));
        assertThrows(IllegalArgumentException.class, () -> ReceiptManifest.parse(header() + "too,few\n"));
        assertEquals(List.of("Manifest has no receipts"),
            ManifestValidator.validate(ReceiptManifest.parse(header()), TODAY, null));
    }

    /** Scripted OCR for the six development fixtures: one clean read and five deliberate failure modes. */
    private ScriptedOcrAdapter scriptedDevelopmentOcr() {
        return new ScriptedOcrAdapter()
            .returns(SyntheticReceiptImages.png("syn-dev-001"),
                "Merchant: Synthetic Rice House\nDate: 2026-09-05\nTotal: SGD 11.00")
            .returns(SyntheticReceiptImages.png("syn-dev-002"),
                "Merchant: SYNTHETIC  KOPI-HOUSE.\nDate: 2026-09-06\nTotal: SGD 4.50")
            .returns(SyntheticReceiptImages.png("syn-dev-003"),
                "Merchant: Synthetic Laksa Stall\nDate: 2026-09-07\nTotal: SGD 18.50")
            .returns(SyntheticReceiptImages.jpeg("syn-dev-004"),
                "Merchant: Synthetic Book Shop\nTotal: SGD 29.99")
            .returns(SyntheticReceiptImages.png("syn-dev-005"),
                "Merchant: Synthetic Juice Bar\nDate: 2026-09-03\nTotal: SGD 6.20")
            .fails(SyntheticReceiptImages.png("syn-dev-006"), new OcrAdapter.OcrUnavailableException("outage"));
    }

    @Test
    void developmentRunUsesProductionParserAndAmountRuleAndScoresHandComputedCounts() throws Exception {
        ReceiptManifest manifest = ReceiptManifest.parse(fixture());
        writeImages(manifest);
        ReceiptBenchmark benchmark = new ReceiptBenchmark(new ReceiptImageValidator(4096),
            scriptedDevelopmentOcr(), new ReceiptParser());
        List<Prediction> predictions = benchmark.predict(manifest, "development", images);
        assertEquals(List.of(Prediction.Outcome.EXTRACTED, Prediction.Outcome.EXTRACTED,
                Prediction.Outcome.NEEDS_HUMAN_CHECK, Prediction.Outcome.NEEDS_HUMAN_CHECK,
                Prediction.Outcome.EXTRACTED, Prediction.Outcome.OCR_UNAVAILABLE),
            predictions.stream().map(Prediction::outcome).toList());
        assertEquals(1850L, predictions.get(2).totalCents());

        ExtractionScorer.Report report = ExtractionScorer.score(manifest, predictions, "development");
        assertEquals(6, report.receipts());
        assertEquals(6, report.syntheticReceipts());
        assertEquals(new ExtractionScorer.FieldScore(6, 3, 4), report.merchant());
        assertEquals(new ExtractionScorer.FieldScore(6, 3, 3), report.date());
        assertEquals(new ExtractionScorer.FieldScore(6, 3, 3), report.total());
        assertEquals(1, report.allFieldsExact());
        assertEquals(2, report.allFieldsNormalized());
        assertEquals(Map.of(Prediction.Outcome.EXTRACTED, 3, Prediction.Outcome.NEEDS_HUMAN_CHECK, 2,
            Prediction.Outcome.OCR_UNAVAILABLE, 1), report.outcomes());
        assertEquals(3, report.humanCorrection());
        assertEquals(0.5, report.humanCorrectionRate());
        assertEquals(1, report.silentErrors());
        String jsonReport = report.toJson();
        assertTrue(jsonReport.contains("\"merchant\": {\"denominator\": 6, \"exact_correct\": 3, \"exact_accuracy\": 0.5000"));
        assertTrue(jsonReport.contains("\"human_correction\": {\"count\": 3, \"denominator\": 6, \"rate\": 0.5000}"));
        assertTrue(jsonReport.contains("\"MISSING\": 0"));

        assertEquals(predictions, Prediction.parse(Prediction.toCsv(predictions)));
        assertThrows(IllegalArgumentException.class, () -> benchmark.predict(manifest, "holdout", images));
    }

    @Test
    void scorerCountsMissingPredictionsAndRejectsCrossSplitOrUnknownRows() throws Exception {
        ReceiptManifest manifest = ReceiptManifest.parse(fixture());
        List<Prediction> one = List.of(new Prediction("syn-test-002", Prediction.Outcome.EXTRACTED,
            "synthetic florist level 2", "2026-09-11", 3500L, "SGD"));
        ExtractionScorer.Report report = ExtractionScorer.score(manifest, one, "test");
        assertEquals(new ExtractionScorer.FieldScore(2, 0, 1), report.merchant());
        assertEquals(new ExtractionScorer.FieldScore(2, 1, 1), report.total());
        assertEquals(1, report.outcomes().get(Prediction.Outcome.MISSING));
        assertEquals(1, report.humanCorrection());
        assertEquals(0, report.silentErrors());
        assertThrows(IllegalArgumentException.class, () -> ExtractionScorer.score(manifest, List.of(
            new Prediction("syn-dev-001", Prediction.Outcome.EXTRACTED, null, null, null, null)), "test"));
        assertThrows(IllegalArgumentException.class, () -> ExtractionScorer.score(manifest, List.of(
            new Prediction("nope", Prediction.Outcome.EXTRACTED, null, null, null, null)), "test"));
        assertThrows(IllegalArgumentException.class, () -> ExtractionScorer.score(manifest,
            List.of(one.get(0), one.get(0)), "test"));
        assertThrows(IllegalArgumentException.class, () -> ExtractionScorer.score(manifest, one, "holdout"));
        assertThrows(IllegalArgumentException.class, () -> Prediction.parse(
            String.join(",", Prediction.HEADER) + "\nx,GUESSED,,,,\n"));
        assertThrows(IllegalArgumentException.class, () -> Prediction.parse(
            String.join(",", Prediction.HEADER) + "\nx,EXTRACTED,,,12.50,\n"));
    }

    @Test
    void cliValidatesAndScoresWithSyntheticWarning() throws Exception {
        Path manifestFile = images.resolve("manifest.csv");
        Files.writeString(manifestFile, fixture());
        Path predictionsFile = images.resolve("predictions.csv");
        Files.writeString(predictionsFile, Prediction.toCsv(List.of(new Prediction("syn-test-001",
            Prediction.Outcome.EXTRACTED, "Synthetic Dim Sum Hall", "2026-09-10", 4210L, "SGD"))));
        Path bad = images.resolve("bad.csv");
        Files.writeString(bad, header() + row("x", "x.png", "Shop", "train", "g")
            + row("y", "y.png", "Shop", "test", "g2"));

        Result valid = cli("validate", manifestFile.toString());
        assertEquals(0, valid.code);
        assertTrue(valid.out.startsWith("OK: 10 receipts {development=6, test=2, train=2}"), valid.out);
        Result missingImages = cli("validate", manifestFile.toString(), "--images", images.toString());
        assertEquals(1, missingImages.code);
        assertTrue(missingImages.err.contains("10 manifest problem(s)"), missingImages.err);
        Result scored = cli("score", manifestFile.toString(), predictionsFile.toString(), "--split", "test");
        assertEquals(0, scored.code, scored.err);
        assertTrue(scored.err.contains("not an OCR accuracy result"));
        assertTrue(scored.out.contains("\"all_fields\": {\"denominator\": 2, \"exact_correct\": 1"), scored.out);
        assertEquals(1, cli("score", bad.toString(), predictionsFile.toString(), "--split", "test").code);
        assertEquals(1, cli("score", manifestFile.toString(), predictionsFile.toString(), "--split", "train").code);
        assertEquals(1, cli("validate", images.resolve("absent.csv").toString()).code);
        assertEquals(2, cli("score", manifestFile.toString()).code);
        assertEquals(2, cli("score", manifestFile.toString(), predictionsFile.toString()).code);
        assertEquals(2, cli("unknown", "x").code);
    }

    private record Result(int code, String out, String err) {}

    private static Result cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ReceiptBenchmarkCli.run(args, TODAY, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
