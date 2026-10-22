package dev.claimflow.benchmark;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;

/** Field comparison rules used by the scorer and the split-leakage check. */
final class Matching {
    private Matching() {}

    /** NFKC, lower case, punctuation removed, whitespace collapsed: "Kopi-Tiam  Pte." -> "kopi tiam pte". */
    static String merchant(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static boolean merchantExact(String label, String predicted) {
        return predicted != null && label.strip().equals(predicted.strip());
    }

    static boolean merchantNormalized(String label, String predicted) {
        return predicted != null && !merchant(predicted).isEmpty() && merchant(label).equals(merchant(predicted));
    }

    static boolean dateExact(String label, String predicted) {
        return predicted != null && label.equals(predicted);
    }

    /** Same calendar date after trimming; non-ISO predictions do not match. */
    static boolean dateNormalized(String label, String predicted) {
        if (predicted == null || !ManifestValidator.isIsoDate(predicted.strip())) return false;
        return LocalDate.parse(label).equals(LocalDate.parse(predicted.strip()));
    }
}
