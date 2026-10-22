package dev.claimflow.benchmark;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Field accuracy and human-correction rate for one split. Every receipt in the split is in every
 * denominator; a missing or unparsed prediction counts as wrong for all three fields.
 */
public final class ExtractionScorer {
    private ExtractionScorer() {}

    public record FieldScore(int denominator, int exactCorrect, int normalizedCorrect) {}

    public record Report(String split, int receipts, int syntheticReceipts, FieldScore merchant,
                         FieldScore date, FieldScore total, int allFieldsExact, int allFieldsNormalized,
                         Map<Prediction.Outcome, Integer> outcomes, int humanCorrection, int silentErrors) {
        /** Share of receipts that a person must handle: anything not auto-EXTRACTED. */
        public double humanCorrectionRate() { return (double) humanCorrection / receipts; }

        /** Auto-EXTRACTED receipts with at least one field that does not match even after normalization. */
        public double silentErrorRate() { return (double) silentErrors / receipts; }

        public String toJson() {
            StringBuilder outcomeJson = new StringBuilder("{");
            for (Prediction.Outcome o : Prediction.Outcome.values()) {
                if (outcomeJson.length() > 1) outcomeJson.append(", ");
                outcomeJson.append('"').append(o.name()).append("\": ").append(outcomes.getOrDefault(o, 0));
            }
            outcomeJson.append('}');
            return "{\n"
                + "  \"split\": \"" + split + "\",\n"
                + "  \"receipts\": " + receipts + ",\n"
                + "  \"synthetic_receipts\": " + syntheticReceipts + ",\n"
                + "  \"fields\": {\n"
                + "    \"merchant\": " + field(merchant) + ",\n"
                + "    \"date\": " + field(date) + ",\n"
                + "    \"total\": " + field(total) + "\n"
                + "  },\n"
                + "  \"all_fields\": {\"denominator\": " + receipts + ", \"exact_correct\": " + allFieldsExact
                + ", \"normalized_correct\": " + allFieldsNormalized + "},\n"
                + "  \"outcomes\": " + outcomeJson + ",\n"
                + "  \"human_correction\": {\"count\": " + humanCorrection + ", \"denominator\": " + receipts
                + ", \"rate\": " + rate(humanCorrection, receipts) + "},\n"
                + "  \"silent_errors\": {\"count\": " + silentErrors + ", \"denominator\": " + receipts
                + ", \"rate\": " + rate(silentErrors, receipts) + "}\n"
                + "}\n";
        }

        private static String field(FieldScore score) {
            return "{\"denominator\": " + score.denominator() + ", \"exact_correct\": " + score.exactCorrect()
                + ", \"exact_accuracy\": " + rate(score.exactCorrect(), score.denominator())
                + ", \"normalized_correct\": " + score.normalizedCorrect()
                + ", \"normalized_accuracy\": " + rate(score.normalizedCorrect(), score.denominator()) + "}";
        }

        private static String rate(int count, int denominator) {
            return String.format(Locale.ROOT, "%.4f", (double) count / denominator);
        }
    }

    public static Report score(ReceiptManifest manifest, List<Prediction> predictions, String split) {
        if (!ManifestValidator.SPLITS.contains(split)) throw new IllegalArgumentException("Unknown split: " + split);
        List<ReceiptManifest.Entry> entries = manifest.split(split);
        if (entries.isEmpty()) throw new IllegalArgumentException("Split " + split + " has no receipts");
        Map<String, ReceiptManifest.Entry> labelled = new HashMap<>();
        for (ReceiptManifest.Entry e : manifest.entries()) labelled.put(e.receiptId(), e);
        Map<String, Prediction> byId = new HashMap<>();
        for (Prediction p : predictions) {
            ReceiptManifest.Entry label = labelled.get(p.receiptId());
            if (label == null) throw new IllegalArgumentException("Prediction for unknown receipt " + p.receiptId());
            if (!label.split().equals(split)) {
                throw new IllegalArgumentException("Prediction " + p.receiptId() + " belongs to split " + label.split());
            }
            if (byId.put(p.receiptId(), p) != null) {
                throw new IllegalArgumentException("Duplicate prediction for " + p.receiptId());
            }
        }
        int synthetic = 0, mExact = 0, mNorm = 0, dExact = 0, dNorm = 0, tExact = 0, tNorm = 0;
        int allExact = 0, allNorm = 0, human = 0, silent = 0;
        Map<Prediction.Outcome, Integer> outcomes = new EnumMap<>(Prediction.Outcome.class);
        for (ReceiptManifest.Entry e : entries) {
            Prediction p = byId.getOrDefault(e.receiptId(),
                new Prediction(e.receiptId(), Prediction.Outcome.MISSING, null, null, null, null));
            if (e.isSynthetic()) synthetic++;
            outcomes.merge(p.outcome(), 1, Integer::sum);
            boolean me = Matching.merchantExact(e.merchant(), p.merchant());
            boolean mn = Matching.merchantNormalized(e.merchant(), p.merchant());
            boolean de = Matching.dateExact(e.date(), p.date());
            boolean dn = Matching.dateNormalized(e.date(), p.date());
            boolean tn = p.totalCents() != null && p.totalCents() == e.totalCentsValue();
            boolean te = tn && e.currency().equals(p.currency());
            mExact += me ? 1 : 0; mNorm += mn ? 1 : 0;
            dExact += de ? 1 : 0; dNorm += dn ? 1 : 0;
            tExact += te ? 1 : 0; tNorm += tn ? 1 : 0;
            if (me && de && te) allExact++;
            boolean allNormalized = mn && dn && tn;
            if (allNormalized) allNorm++;
            if (p.outcome() != Prediction.Outcome.EXTRACTED) human++;
            else if (!allNormalized) silent++;
        }
        int n = entries.size();
        return new Report(split, n, synthetic, new FieldScore(n, mExact, mNorm), new FieldScore(n, dExact, dNorm),
            new FieldScore(n, tExact, tNorm), allExact, allNorm, Map.copyOf(outcomes), human, silent);
    }
}
