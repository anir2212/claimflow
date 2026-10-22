package dev.claimflow;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Drives the OCR adapter seam with a scripted adapter and synthetic images. Every receipt here is
 * an authored development fixture; no OCR engine or real receipt is involved.
 */
@SpringBootTest(properties = {
    "claimflow.extraction.enabled=false",
    "claimflow.receipt-image.max-bytes=4096",
    "spring.datasource.url=jdbc:h2:mem:claimflow-ocr;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@Import(ReceiptImageIntegrationTest.ScriptedOcrConfig.class)
class ReceiptImageIntegrationTest {
    @TestConfiguration
    static class ScriptedOcrConfig {
        @Bean @Primary ScriptedOcrAdapter scriptedOcrAdapter() { return new ScriptedOcrAdapter(); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ScriptedOcrAdapter ocr;
    @Autowired ExtractionProcessor extraction;
    @Autowired ExtractionJobRepository jobs;
    @Autowired ClaimRepository claims;

    private String token(String user) throws Exception {
        String body = json.writeValueAsString(new AuthController.Credentials(user, "local-only"));
        String response = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).path("access_token").asText();
    }

    private ResultActions upload(String token, String key, long cents, byte[] image, String type) throws Exception {
        return mvc.perform(multipart("/api/claims/image")
            .file(new MockMultipartFile("receipt", "receipt.png", type, image))
            .param("category", "meal").param("amountCents", Long.toString(cents))
            .header("Authorization", token).header("Idempotency-Key", key));
    }

    private String id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asText();
    }

    private static String receipt(String merchant, String date, String total) {
        return "Merchant: " + merchant + "\nDate: " + date + "\nTotal: SGD " + total;
    }

    private String state(String id) { return claims.findById(id).orElseThrow().getState().name(); }

    @Test
    void ocrTextIsQueuedThroughExistingRulesAndAudited() throws Exception {
        String employee = token("employee");
        String merchant = "Synthetic OCR Cafe " + UUID.randomUUID();
        byte[] image = SyntheticReceiptImages.png(merchant);
        ocr.returns(image, "  " + receipt(merchant, "2026-10-11", "14.20") + "\r\n");
        String id = id(upload(employee, "ocr-ok-" + merchant, 1420, image, "image/png")
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("SUBMITTED"))));
        ExtractionJob job = jobs.findById(id).orElseThrow();
        assertEquals(ReceiptImageValidator.sha256(image), job.getInputHash());
        assertEquals(receipt(merchant, "2026-10-11", "14.20"), job.getReceiptText());
        extraction.processOne(id);
        assertEquals("IN_REVIEW", state(id));
        assertEquals("", jobs.findById(id).orElseThrow().getReceiptText());
        mvc.perform(get("/api/claims/" + id + "/audit").header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(2)))
            .andExpect(jsonPath("$[0].detail", containsString("Image OCR text queued (scripted-test-ocr)")))
            .andExpect(jsonPath("$[1].action", is("EXTRACTED")));
        upload(token("manager"), "ocr-role-" + merchant, 1420, image, "image/png").andExpect(status().isForbidden());
    }

    @Test
    void repeatedIdempotencyKeyReplaysWithoutSecondOcrCallAndRejectsReuse() throws Exception {
        String employee = token("employee");
        String merchant = "Synthetic Replay Deli " + UUID.randomUUID();
        byte[] image = SyntheticReceiptImages.png(merchant);
        byte[] other = SyntheticReceiptImages.png(merchant + " other");
        ocr.returns(image, receipt(merchant, "2026-10-12", "9.90"))
           .returns(other, receipt(merchant + " B", "2026-10-12", "9.90"));
        String key = "ocr-replay-" + merchant;
        String id = id(upload(employee, key, 990, image, "image/png").andExpect(status().isOk()));
        int callsAfterFirst = ocr.calls();
        upload(employee, key, 990, image, "image/png").andExpect(status().isOk()).andExpect(jsonPath("$.id", is(id)));
        assertEquals(callsAfterFirst, ocr.calls());
        upload(employee, key, 990, other, "image/png").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("Idempotency key reused with different claim")));
        upload(employee, key, 1990, image, "image/png").andExpect(status().isBadRequest());
        String textBody = json.writeValueAsString(new ClaimService.Submit("meal", 990, receipt(merchant, "2026-10-12", "9.90")));
        mvc.perform(post("/api/claims/async").header("Authorization", employee).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(textBody)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/claims").header("Authorization", employee).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(textBody)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("Idempotency key reused with an async claim")));
        assertEquals(callsAfterFirst, ocr.calls());
        assertEquals(id, claims.findByIdempotencyKey(key).orElseThrow().getId());
    }

    @Test
    void mismatchedOcrTotalGoesToManagerCorrectionNotReview() throws Exception {
        String employee = token("employee");
        String manager = token("manager");
        String merchant = "Synthetic Mismatch Grill " + UUID.randomUUID();
        byte[] image = SyntheticReceiptImages.jpeg(merchant);
        ocr.returns(image, receipt(merchant, "2026-10-13", "31.00"));
        String id = id(upload(employee, "ocr-mismatch-" + merchant, 1300, image, "image/jpeg")
            .andExpect(status().isOk()));
        extraction.processOne(id);
        assertEquals("NEEDS_HUMAN_CHECK", state(id));
        ExtractionJob job = jobs.findById(id).orElseThrow();
        assertEquals(ExtractionJob.Status.DEAD, job.getStatus());
        assertEquals("Claim amount does not match receipt total", job.getLastError());
        String stillWrong = json.writeValueAsString(new ClaimController.ReceiptCheck(receipt(merchant, "2026-10-13", "31.00")));
        mvc.perform(post("/api/claims/" + id + "/receipt-check").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content(stillWrong)).andExpect(status().isBadRequest());
        String corrected = json.writeValueAsString(new ClaimController.ReceiptCheck(receipt(merchant, "2026-10-13", "13.00")));
        mvc.perform(post("/api/claims/" + id + "/receipt-check").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content(corrected))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("IN_REVIEW")));
        mvc.perform(get("/api/claims/" + id + "/audit").header("Authorization", employee))
            .andExpect(jsonPath("$[1].action", is("NEEDS_HUMAN_CHECK")))
            .andExpect(jsonPath("$[2].action", is("RECEIPT_CHECKED")));
    }

    @Test
    void duplicateReceiptFromSecondImageOrTypedTextIsCaught() throws Exception {
        String employee = token("employee");
        String merchant = "Synthetic Duplicate Bakery " + UUID.randomUUID();
        String text = receipt(merchant, "2026-10-14", "7.80");
        byte[] first = SyntheticReceiptImages.png(merchant + " photo 1");
        byte[] second = SyntheticReceiptImages.png(merchant + " photo 2");
        ocr.returns(first, text).returns(second, text);
        String a = id(upload(employee, "ocr-dup-a-" + merchant, 780, first, "image/png").andExpect(status().isOk()));
        String b = id(upload(employee, "ocr-dup-b-" + merchant, 780, second, "image/png").andExpect(status().isOk()));
        extraction.processOne(a);
        extraction.processOne(b);
        assertEquals("IN_REVIEW", state(a));
        assertEquals("NEEDS_HUMAN_CHECK", state(b));
        assertEquals("Duplicate receipt and expense details", jobs.findById(b).orElseThrow().getLastError());
        String typed = json.writeValueAsString(new ClaimService.Submit("meal", 780, text));
        mvc.perform(post("/api/claims").header("Authorization", employee)
            .header("Idempotency-Key", "ocr-dup-typed-" + merchant).contentType(MediaType.APPLICATION_JSON)
            .content(typed)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("Duplicate receipt and expense details")));
    }

    @Test
    void malformedImagesAndOcrFailuresCreateNoClaim() throws Exception {
        String employee = token("employee");
        String nonce = UUID.randomUUID().toString();
        byte[] text = "Merchant: Not An Image\nDate: 2026-10-15\nTotal: SGD 5.00".getBytes(StandardCharsets.UTF_8);
        byte[] oversized = Arrays.copyOf(SyntheticReceiptImages.png("oversized " + nonce), 4097);
        byte[] unreadable = SyntheticReceiptImages.png("unreadable " + nonce);
        byte[] outage = SyntheticReceiptImages.png("outage " + nonce);
        byte[] blank = SyntheticReceiptImages.png("blank " + nonce);
        byte[] png = SyntheticReceiptImages.png("mislabelled " + nonce);
        ocr.fails(unreadable, new OcrAdapter.UnreadableReceiptImageException("Image could not be decoded"))
           .fails(outage, new OcrAdapter.OcrUnavailableException("Scripted OCR outage"))
           .returns(blank, " \n\t ");
        int callsBefore = ocr.calls();
        upload(employee, "bad-empty-" + nonce, 500, new byte[0], "image/png").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("Receipt image is empty")));
        upload(employee, "bad-text-" + nonce, 500, text, "image/png").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("Receipt image must be PNG or JPEG")));
        upload(employee, "bad-big-" + nonce, 500, oversized, "image/png").andExpect(status().isPayloadTooLarge());
        upload(employee, "bad-type-" + nonce, 500, png, "image/jpeg").andExpect(status().isBadRequest());
        assertEquals(callsBefore, ocr.calls());
        upload(employee, "bad-unreadable-" + nonce, 500, unreadable, "image/png").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("Image could not be decoded")));
        upload(employee, "bad-outage-" + nonce, 500, outage, "image/png").andExpect(status().isServiceUnavailable());
        upload(employee, "bad-blank-" + nonce, 500, blank, "image/png").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("No text was recognized in the receipt image")));
        upload(employee, "", 500, png, "image/png").andExpect(status().isBadRequest());
        for (String key : new String[] {"bad-empty-", "bad-text-", "bad-big-", "bad-type-", "bad-unreadable-",
            "bad-outage-", "bad-blank-"}) {
            assertFalse(claims.findByIdempotencyKey(key + nonce).isPresent(), key);
        }
        // The outage left no claim, so the same key can be retried once OCR recovers.
        ocr.returns(outage, receipt("Synthetic Retry Kiosk " + nonce, "2026-10-15", "5.00"));
        upload(employee, "bad-outage-" + nonce, 500, outage, "image/png").andExpect(status().isOk())
            .andExpect(jsonPath("$.state", is("SUBMITTED")));
        assertTrue(claims.findByIdempotencyKey("bad-outage-" + nonce).isPresent());
    }
}
