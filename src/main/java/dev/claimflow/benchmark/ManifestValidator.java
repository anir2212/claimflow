package dev.claimflow.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Returns every manifest problem; an empty list means the manifest may be scored. */
public final class ManifestValidator {
    public static final Set<String> SPLITS = Set.of("train", "development", "test");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Set<String> REDACTION = Set.of("REDACTED", "NOT_REQUIRED");

    private ManifestValidator() {}

    /**
     * @param today     date used for the retention check
     * @param imageRoot when non-null, every image must exist as a regular file under this directory
     */
    public static List<String> validate(ReceiptManifest manifest, LocalDate today, Path imageRoot) {
        List<String> errors = new ArrayList<>();
        if (manifest.entries().isEmpty()) errors.add("Manifest has no receipts");
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        Map<String, String> groupSplit = new HashMap<>();
        Map<String, String> merchantSplit = new HashMap<>();
        Map<String, Set<Boolean>> syntheticBySplit = new HashMap<>();
        for (ReceiptManifest.Entry e : manifest.entries()) {
            String at = "row " + e.row() + " (" + e.receiptId() + "): ";
            if (!ID.matcher(e.receiptId()).matches()) errors.add(at + "receipt_id must match [A-Za-z0-9_-]{1,64}");
            else if (!ids.add(e.receiptId())) errors.add(at + "duplicate receipt_id");
            checkPath(e, at, imageRoot, paths, errors);
            String merchant = e.merchant() == null ? "" : e.merchant().strip();
            if (merchant.isEmpty() || merchant.length() > 200) errors.add(at + "merchant must be 1 to 200 characters");
            if (!isIsoDate(e.date())) errors.add(at + "date must be ISO yyyy-MM-dd");
            if (!isPositiveLong(e.totalCents())) errors.add(at + "total_cents must be a positive integer");
            if (!CURRENCY.matcher(e.currency()).matches()) errors.add(at + "currency must be a 3-letter ISO 4217 code");
            boolean knownSplit = SPLITS.contains(e.split());
            if (!knownSplit) errors.add(at + "split must be train, development or test");
            if (e.groupId().isEmpty()) errors.add(at + "group_id is required");
            if (e.source().isEmpty()) errors.add(at + "source is required");
            if (e.authorization().isEmpty()) errors.add(at + "authorization reference is required");
            if (!e.synthetic().equals("true") && !e.synthetic().equals("false")) {
                errors.add(at + "synthetic must be true or false");
            }
            if (!REDACTION.contains(e.redaction())) errors.add(at + "redaction must be REDACTED or NOT_REQUIRED");
            if (!isIsoDate(e.retainUntil())) errors.add(at + "retain_until must be ISO yyyy-MM-dd");
            else if (LocalDate.parse(e.retainUntil()).isBefore(today)) {
                errors.add(at + "retention expired on " + e.retainUntil() + "; delete the image and row");
            }
            if (!knownSplit) continue;
            leak(groupSplit, e.groupId(), e.split(), at + "group_id '" + e.groupId() + "'", errors);
            if (!merchant.isEmpty()) {
                leak(merchantSplit, Matching.merchant(merchant), e.split(), at + "merchant '" + merchant + "'", errors);
            }
            syntheticBySplit.computeIfAbsent(e.split(), s -> new HashSet<>()).add(e.isSynthetic());
        }
        syntheticBySplit.forEach((split, kinds) -> {
            if (split.equals("test") && kinds.size() > 1) {
                errors.add("test split mixes synthetic and real receipts; score them separately");
            }
        });
        return errors;
    }

    private static void checkPath(ReceiptManifest.Entry e, String at, Path root, Set<String> paths,
                                  List<String> errors) {
        String path = e.imagePath();
        String lower = path.toLowerCase();
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.contains("..")
            || path.contains(":") || !(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            errors.add(at + "image_path must be a relative .png/.jpg/.jpeg path without '..'");
            return;
        }
        if (!paths.add(path)) errors.add(at + "image_path is used by another row");
        if (root != null && !Files.isRegularFile(root.resolve(path))) errors.add(at + "image file not found");
    }

    private static void leak(Map<String, String> seen, String key, String split, String what, List<String> errors) {
        String previous = seen.putIfAbsent(key, split);
        if (previous != null && !previous.equals(split)) {
            errors.add(what + " appears in both " + previous + " and " + split + " splits");
        }
    }

    static boolean isIsoDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException | NullPointerException invalid) {
            return false;
        }
    }

    private static boolean isPositiveLong(String value) {
        try {
            return value.matches("\\d{1,18}") && Long.parseLong(value) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
