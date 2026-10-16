package dev.claimflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Deterministic text baseline; image OCR and LLM extraction are later milestones. */
@Component
public class ReceiptParser {
    private static final Pattern MERCHANT = Pattern.compile("(?im)^merchant:\\s*(.+?)\\s*$");
    private static final Pattern DATE = Pattern.compile("(?im)^date:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$");
    private static final Pattern TOTAL = Pattern.compile("(?im)^total:\\s*SGD\\s*(\\d+)\\.(\\d{2})\\s*$");

    public record Parsed(String merchant, LocalDate date, long cents, String hash) {}

    public Parsed parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Receipt text is empty");
        Matcher merchant = MERCHANT.matcher(text);
        Matcher date = DATE.matcher(text);
        Matcher total = TOTAL.matcher(text);
        if (!merchant.find() || !date.find() || !total.find()) {
            throw new IllegalArgumentException("Receipt needs human check: merchant, date and SGD total required");
        }
        if (merchant.group(1).isBlank() || merchant.group(1).strip().length() > 200) {
            throw new IllegalArgumentException("Receipt merchant must be 1 to 200 characters");
        }
        long cents = Math.addExact(Math.multiplyExact(Long.parseLong(total.group(1)), 100),
                                    Long.parseLong(total.group(2)));
        if (cents <= 0) throw new IllegalArgumentException("Receipt total must be positive");
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(date.group(1));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid receipt date", invalid);
        }
        String normalized = text.strip().replaceAll("\\s+", " ").toLowerCase();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                normalized.getBytes(StandardCharsets.UTF_8));
            return new Parsed(merchant.group(1).strip(), parsedDate, cents,
                              HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
