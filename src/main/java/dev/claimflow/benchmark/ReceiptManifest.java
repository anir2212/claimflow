package dev.claimflow.benchmark;

import java.util.List;
import java.util.Map;

/**
 * Labelled receipt benchmark manifest. One CSV row per receipt image; the image itself lives outside
 * Git under an operator-controlled directory. Columns, in order:
 *
 * <pre>
 * receipt_id     unique ID, [A-Za-z0-9_-]{1,64}
 * image_path     path relative to the image root; .png/.jpg/.jpeg; no "..", no absolute path
 * merchant       ground-truth merchant name as printed (1-200 characters)
 * date           ground-truth expense date, ISO yyyy-MM-dd
 * total_cents    ground-truth total in minor units, positive integer
 * currency       ISO 4217 code, three upper-case letters (the current parser reads SGD only)
 * split          train | development | test
 * group_id       leakage group (same merchant or same receipt source); never crosses splits
 * source         where the image came from (person, dataset or synthetic generator)
 * authorization  consent, licence or approval reference permitting this use
 * synthetic      true for generated fixtures, false for an authorized real receipt
 * redaction      REDACTED | NOT_REQUIRED (personal data removed, or none present)
 * retain_until   ISO date after which the image and labels must be deleted
 * </pre>
 */
public record ReceiptManifest(List<Entry> entries) {
    public static final List<String> HEADER = List.of("receipt_id", "image_path", "merchant", "date",
        "total_cents", "currency", "split", "group_id", "source", "authorization", "synthetic",
        "redaction", "retain_until");

    /** Raw string fields; {@link ManifestValidator} decides whether they are acceptable. */
    public record Entry(int row, String receiptId, String imagePath, String merchant, String date,
                        String totalCents, String currency, String split, String groupId, String source,
                        String authorization, String synthetic, String redaction, String retainUntil) {
        public boolean isSynthetic() { return "true".equals(synthetic); }
        public long totalCentsValue() { return Long.parseLong(totalCents); }
    }

    public static ReceiptManifest parse(String csv) {
        List<Map<String, String>> rows = Csv.read(csv, HEADER);
        java.util.ArrayList<Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            entries.add(new Entry(i + 2, r.get("receipt_id").strip(), r.get("image_path").strip(),
                r.get("merchant"), r.get("date").strip(), r.get("total_cents").strip(),
                r.get("currency").strip(), r.get("split").strip(), r.get("group_id").strip(),
                r.get("source").strip(), r.get("authorization").strip(), r.get("synthetic").strip(),
                r.get("redaction").strip(), r.get("retain_until").strip()));
        }
        return new ReceiptManifest(List.copyOf(entries));
    }

    public List<Entry> split(String split) {
        return entries.stream().filter(entry -> entry.split().equals(split)).toList();
    }
}
