package dev.claimflow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Test-only OCR stand-in: returns authored text for a known image hash. It reads no pixels and
 * measures nothing about real OCR quality; it exists to drive the adapter seam deterministically.
 */
public class ScriptedOcrAdapter implements OcrAdapter {
    public static final String ENGINE = "scripted-test-ocr";
    private final Map<String, Supplier<OcrText>> script = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public ScriptedOcrAdapter returns(byte[] image, String text) {
        script.put(ReceiptImageValidator.sha256(image), () -> new OcrText(text, ENGINE));
        return this;
    }

    public ScriptedOcrAdapter fails(byte[] image, RuntimeException error) {
        script.put(ReceiptImageValidator.sha256(image), () -> { throw error; });
        return this;
    }

    public int calls() { return calls.get(); }

    @Override
    public OcrText recognize(ReceiptImageValidator.ReceiptImage image) {
        calls.incrementAndGet();
        Supplier<OcrText> result = script.get(image.sha256());
        if (result == null) throw new UnreadableReceiptImageException("Scripted OCR has no text for this image");
        return result.get();
    }
}
