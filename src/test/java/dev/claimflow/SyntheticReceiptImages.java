package dev.claimflow;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

/**
 * SYNTHETIC DEVELOPMENT FIXTURES ONLY. Builds small, valid image files whose pixels are derived
 * from a label so each label has distinct bytes. They contain no receipt content; the scripted
 * test OCR adapter maps their hashes to authored text. Never use them as OCR accuracy evidence.
 */
public final class SyntheticReceiptImages {
    private SyntheticReceiptImages() {}

    public static byte[] png(String label) { return encode(label, "png"); }

    public static byte[] jpeg(String label) { return encode(label, "jpg"); }

    private static byte[] encode(String label, String format) {
        byte[] seed = ReceiptImageValidator.sha256(label.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 64; i++) {
            int value = seed[i % seed.length] & 0xFF;
            image.setRGB(i % 8, i / 8, (value << 16) | ((value * 7 & 0xFF) << 8) | (value * 13 & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) throw new IllegalStateException("No writer for " + format);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }
}
