package dev.claimflow;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Checks uploaded receipt bytes before any OCR engine sees them. It does not decode pixels. */
@Component
public class ReceiptImageValidator {
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** Validated bytes, detected media type and SHA-256 of the exact bytes. */
    public record ReceiptImage(byte[] bytes, String mediaType, String sha256) {}

    /** Mapped to HTTP 413 by {@link ApiErrors}. */
    public static class ReceiptImageTooLargeException extends IllegalArgumentException {
        public ReceiptImageTooLargeException(String message) { super(message); }
    }

    private final long maxBytes;

    public ReceiptImageValidator(@Value("${claimflow.receipt-image.max-bytes:5242880}") long maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("Receipt image limit must be positive");
        this.maxBytes = maxBytes;
    }

    public long maxBytes() { return maxBytes; }

    public ReceiptImage validate(byte[] bytes, String declaredType) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Receipt image is empty");
        if (bytes.length > maxBytes) {
            throw new ReceiptImageTooLargeException("Receipt image exceeds " + maxBytes + " bytes");
        }
        String detected = startsWith(bytes, PNG) ? "image/png" : startsWith(bytes, JPEG) ? "image/jpeg" : null;
        if (detected == null) throw new IllegalArgumentException("Receipt image must be PNG or JPEG");
        String declared = declaredType == null ? "" : declaredType.split(";", 2)[0].strip().toLowerCase();
        if (declared.equals("image/jpg")) declared = "image/jpeg";
        if (!declared.isEmpty() && !declared.equals("application/octet-stream") && !declared.equals(detected)) {
            throw new IllegalArgumentException("Receipt image content type does not match its bytes");
        }
        return new ReceiptImage(bytes, detected, sha256(bytes));
    }

    /** OCR text normalization: Unicode NFKC, LF line endings, no control characters, trimmed lines. */
    public static String normalizeText(String text) {
        if (text == null) return "";
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
            .replaceAll("[\\p{Cc}&&[^\\n]]", "");
        StringBuilder out = new StringBuilder();
        boolean lastBlank = true;
        for (String line : normalized.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (!lastBlank) out.append('\n');
                lastBlank = true;
            } else {
                out.append(stripped).append('\n');
                lastBlank = false;
            }
        }
        return out.toString().strip();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }
}
