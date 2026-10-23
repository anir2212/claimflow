package dev.claimflow;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Checks the Prometheus counters and decision histogram against known local actions on H2. */
@SpringBootTest(properties = {
    "claimflow.extraction.enabled=false",
    "management.prometheus.metrics.export.enabled=true",
    "claimflow.receipt-image.max-bytes=4096",
    "spring.datasource.url=jdbc:h2:mem:claimflow-metrics;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ClaimMetricsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;
    @Autowired ClaimRepository claims;
    @Autowired TransactionTemplate transactions;
    @Autowired ApiErrors errors;

    private String token(String user) throws Exception {
        String body = json.writeValueAsString(new AuthController.Credentials(user, "local-only"));
        String response = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).path("access_token").asText();
    }

    private double transitions(ClaimState from, ClaimState to) {
        return meters.get("claimflow.claim.transitions").tag("from", from.name()).tag("to", to.name())
            .counter().count();
    }

    private double uploads(String outcome) {
        return meters.get("claimflow.receipt.uploads").tag("outcome", outcome).counter().count();
    }

    private Timer decisions(String outcome) {
        return meters.get("claimflow.approval.latency").tag("outcome", outcome).timer();
    }

    private String submit(String employee, long cents, String merchant) throws Exception {
        String body = json.writeValueAsString(new ClaimService.Submit("meal", cents,
            "Merchant: " + merchant + "\nDate: 2026-10-20\nTotal: SGD " + String.format("%d.%02d", cents / 100, cents % 100)));
        String response = mvc.perform(post("/api/claims").header("Authorization", employee)
                .header("Idempotency-Key", "metrics-" + merchant).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asText();
    }

    @Test
    void committedTransitionsAndDecisionLatencyAreCountedOnce() throws Exception {
        String employee = token("employee");
        String manager = token("manager");
        String finance = token("finance");
        double submitted = transitions(ClaimState.DRAFT, ClaimState.SUBMITTED);
        double reviewed = transitions(ClaimState.EXTRACTED, ClaimState.IN_REVIEW);
        double approved = transitions(ClaimState.IN_REVIEW, ClaimState.APPROVED);
        double rejected = transitions(ClaimState.IN_REVIEW, ClaimState.REJECTED);
        double paid = transitions(ClaimState.APPROVED, ClaimState.PAID);
        long approvedDecisions = decisions("approved").count();
        long rejectedDecisions = decisions("rejected").count();

        String a = submit(employee, 1210, "Metrics Cafe " + UUID.randomUUID());
        String b = submit(employee, 1320, "Metrics Deli " + UUID.randomUUID());
        mvc.perform(post("/api/claims/" + a + "/decision").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/claims/" + b + "/decision").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":false}")).andExpect(status().isOk());
        // A second decision is an illegal transition: 409, and nothing is counted.
        mvc.perform(post("/api/claims/" + a + "/decision").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":false}")).andExpect(status().isConflict());
        mvc.perform(post("/api/claims/" + a + "/paid").header("Authorization", finance)).andExpect(status().isOk());

        assertEquals(submitted + 2, transitions(ClaimState.DRAFT, ClaimState.SUBMITTED));
        assertEquals(reviewed + 2, transitions(ClaimState.EXTRACTED, ClaimState.IN_REVIEW));
        assertEquals(approved + 1, transitions(ClaimState.IN_REVIEW, ClaimState.APPROVED));
        assertEquals(rejected + 1, transitions(ClaimState.IN_REVIEW, ClaimState.REJECTED));
        assertEquals(paid + 1, transitions(ClaimState.APPROVED, ClaimState.PAID));
        assertEquals(approvedDecisions + 1, decisions("approved").count());
        assertEquals(rejectedDecisions + 1, decisions("rejected").count());

        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
            .andExpect(content().string(containsString("claimflow_claim_transitions_total{from=\"IN_REVIEW\",to=\"APPROVED\"}")))
            .andExpect(content().string(containsString("claimflow_claim_transitions_total{from=\"SUBMITTED\",to=\"NEEDS_HUMAN_CHECK\"} 0.0")))
            .andExpect(content().string(containsString("claimflow_approval_latency_seconds_bucket{outcome=\"approved\",le=\"172800.0\"}")))
            .andExpect(content().string(containsString("claimflow_approval_latency_seconds_count{outcome=\"rejected\"}")));
    }

    @Test
    void rolledBackTransitionIsNotCounted() throws Exception {
        String id = submit(token("employee"), 1430, "Metrics Rollback " + UUID.randomUUID());
        double rejected = transitions(ClaimState.IN_REVIEW, ClaimState.REJECTED);
        long rejectedDecisions = decisions("rejected").count();
        transactions.executeWithoutResult(status -> {
            Claim claim = claims.findById(id).orElseThrow();
            claim.move(ClaimState.REJECTED);
            claims.flush(); // the UPDATE runs and the JPA callback fires inside the transaction
            status.setRollbackOnly();
        });
        assertEquals("IN_REVIEW", claims.findById(id).orElseThrow().getState().name());
        assertEquals(rejected, transitions(ClaimState.IN_REVIEW, ClaimState.REJECTED));
        assertEquals(rejectedDecisions, decisions("rejected").count());
    }

    @Test
    void receiptUploadOutcomesAreCounted() throws Exception {
        String employee = token("employee");
        String nonce = UUID.randomUUID().toString();
        double unavailable = uploads("ocr_unavailable");
        double invalid = uploads("invalid");
        double tooLarge = uploads("too_large");
        byte[] png = SyntheticReceiptImages.png("metrics " + nonce);
        byte[] text = "not an image".getBytes(StandardCharsets.UTF_8);
        byte[] oversized = Arrays.copyOf(png, 5000);
        for (byte[] body : new byte[][] {png, text, oversized}) {
            mvc.perform(multipart("/api/claims/image")
                .file(new MockMultipartFile("receipt", "r.png", "image/png", body))
                .param("category", "meal").param("amountCents", "500")
                .header("Authorization", employee).header("Idempotency-Key", "metrics-upload-" + UUID.randomUUID()));
        }
        errors.tooLarge(new MaxUploadSizeExceededException(6 * 1024 * 1024));
        assertEquals(unavailable + 1, uploads("ocr_unavailable"));
        assertEquals(invalid + 1, uploads("invalid"));
        assertEquals(tooLarge + 2, uploads("too_large"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
            .andExpect(content().string(containsString("claimflow_receipt_uploads_total{outcome=\"ocr_unavailable\"}")))
            .andExpect(content().string(containsString("claimflow_receipt_uploads_total{outcome=\"queued\"} 0.0")));
    }
}
