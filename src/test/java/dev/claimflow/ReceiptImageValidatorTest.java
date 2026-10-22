package dev.claimflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ReceiptImageValidatorTest {
    private final ReceiptImageValidator validator = new ReceiptImageValidator(1024);

    @Test
    void acceptsPngAndJpegByContentAndRecordsExactByteHash() {
        byte[] png = SyntheticReceiptImages.png("validator-png");
        var accepted = validator.validate(png, "image/png");
        assertEquals("image/png", accepted.mediaType());
        assertEquals(ReceiptImageValidator.sha256(png), accepted.sha256());
        assertEquals("image/jpeg", validator.validate(SyntheticReceiptImages.jpeg("validator-jpg"), "image/jpg").mediaType());
        assertEquals("image/png", validator.validate(png, null).mediaType());
        assertEquals("image/png", validator.validate(png, "application/octet-stream").mediaType());
    }

    @Test
    void rejectsEmptyOversizedNonImageAndMislabelledBytes() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null, "image/png"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new byte[0], "image/png"));
        byte[] oversized = Arrays.copyOf(SyntheticReceiptImages.png("big"), 1025);
        assertThrows(ReceiptImageValidator.ReceiptImageTooLargeException.class,
            () -> validator.validate(oversized, "image/png"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
            "Merchant: Cafe\nDate: 2026-10-01\nTotal: SGD 1.00".getBytes(StandardCharsets.UTF_8), "image/png"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
            "%PDF-1.7".getBytes(StandardCharsets.US_ASCII), null));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new byte[] {(byte) 0x89, 'P'}, null));
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate(SyntheticReceiptImages.png("label"), "image/jpeg"));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptImageValidator(0));
    }

    @Test
    void normalizesOcrLayoutWithoutChangingFieldValues() {
        String ocr = "  Ｍｅｒｃｈａｎｔ: Kopi\u0007 Stall \r\n\r\n\r\n"
            + "\tDate: 2026-10-01\rTotal: SGD １２.50  \n\n";
        assertEquals("Merchant: Kopi Stall\n\nDate: 2026-10-01\nTotal: SGD 12.50",
            ReceiptImageValidator.normalizeText(ocr));
        assertEquals("", ReceiptImageValidator.normalizeText(null));
        assertEquals("", ReceiptImageValidator.normalizeText(" \n\t\n"));
        assertEquals(1250, new ReceiptParser().parse(ReceiptImageValidator.normalizeText(ocr)).cents());
    }
}
