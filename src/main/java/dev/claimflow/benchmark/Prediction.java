package dev.claimflow.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One pipeline outcome for one receipt; fields are null when nothing was parsed. */
public record Prediction(String receiptId, Outcome outcome, String merchant, String date,
                         Long totalCents, String currency) {
    /**
     * EXTRACTED: parser and amount rule accepted it, so it would reach manager review untouched.
     * NEEDS_HUMAN_CHECK: text reached the parser but failed it or the amount rule.
     * REJECTED: image or recognized text refused before a claim exists (400).
     * OCR_UNAVAILABLE: engine failure (503). MISSING: no prediction row was supplied.
     */
    public enum Outcome { EXTRACTED, NEEDS_HUMAN_CHECK, REJECTED, OCR_UNAVAILABLE, MISSING }

    public static final List<String> HEADER =
        List.of("receipt_id", "outcome", "merchant", "date", "total_cents", "currency");

    public static List<Prediction> parse(String csv) {
        List<Prediction> result = new ArrayList<>();
        for (Map<String, String> r : Csv.read(csv, HEADER)) {
            String cents = r.get("total_cents").strip();
            Outcome outcome;
            try {
                outcome = Outcome.valueOf(r.get("outcome").strip());
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown prediction outcome: " + r.get("outcome"));
            }
            if (!cents.isEmpty() && !cents.matches("-?\\d{1,18}")) {
                throw new IllegalArgumentException("total_cents must be an integer: " + cents);
            }
            result.add(new Prediction(r.get("receipt_id").strip(), outcome, blankToNull(r.get("merchant")),
                blankToNull(r.get("date")), cents.isEmpty() ? null : Long.parseLong(cents),
                blankToNull(r.get("currency"))));
        }
        return result;
    }

    public static String toCsv(List<Prediction> predictions) {
        StringBuilder out = new StringBuilder(String.join(",", HEADER)).append('\n');
        for (Prediction p : predictions) {
            out.append(String.join(",", quote(p.receiptId()), p.outcome().name(), quote(p.merchant()),
                quote(p.date()), p.totalCents() == null ? "" : p.totalCents().toString(), quote(p.currency())))
                .append('\n');
        }
        return out.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String quote(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
