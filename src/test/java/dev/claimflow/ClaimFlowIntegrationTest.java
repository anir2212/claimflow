package dev.claimflow;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "claimflow.extraction.enabled=false")
@AutoConfigureMockMvc
class ClaimFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ExtractionProcessor extraction;
    @Autowired ExtractionFailureService failures;
    @Autowired ExtractionJobRepository jobs;
    @Autowired ClaimSubmissionCoordinator submissions;
    @Autowired ClaimRepository claimRepository;

    private String token(String user) throws Exception {
        String body = json.writeValueAsString(new AuthController.Credentials(user, "local-only"));
        String response = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).path("access_token").asText();
    }

    @Test
    void submissionIsIdempotentAuditedAndRoleRestricted() throws Exception {
        String employee = token("employee");
        String manager = token("manager");
        String finance = token("finance");
        String receipt = "Merchant: Cafe One\nDate: 2026-10-01\nTotal: SGD 12.50";
        String body = json.writeValueAsString(new ClaimService.Submit("meal", 1250, receipt));
        String first = mvc.perform(post("/api/claims").header("Authorization", employee)
                .header("Idempotency-Key", "test-key-1").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("IN_REVIEW")))
            .andReturn().getResponse().getContentAsString();
        String id = json.readTree(first).path("id").asText();
        mvc.perform(post("/api/claims").header("Authorization", employee)
            .header("Idempotency-Key", "test-key-1").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id", is(id)));
        mvc.perform(post("/api/claims").header("Authorization", employee)
            .header("Idempotency-Key", "test-key-2").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/claims/" + id + "/decision").header("Authorization", employee)
            .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/claims/" + id + "/decision").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/claims/" + id + "/decision").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("APPROVED")));
        mvc.perform(post("/api/claims/" + id + "/paid").header("Authorization", finance))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("PAID")));
        mvc.perform(get("/api/claims/stats").header("Authorization", finance))
            .andExpect(status().isOk()).andExpect(jsonPath("$.byStateCount.PAID", is(1)));
        mvc.perform(get("/api/claims/stats").header("Authorization", employee))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/claims/" + id + "/audit").header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(3)))
            .andExpect(jsonPath("$[0].action", is("SUBMITTED")))
            .andExpect(jsonPath("$[1].action", is("APPROVED")))
            .andExpect(jsonPath("$[2].action", is("PAID")));
    }

    @Test
    void hardPolicyCannotBeOverriddenAndUnknownQuestionsAbstain() throws Exception {
        String employee = token("employee");
        String manager = token("manager");
        String receipt = "Merchant: Costly Cafe\nDate: 2026-10-02\nTotal: SGD 70.00";
        String body = json.writeValueAsString(new ClaimService.Submit("meal", 7000, receipt));
        String result = mvc.perform(post("/api/claims").header("Authorization", employee)
            .header("Idempotency-Key", "test-key-3").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).path("id").asText();
        mvc.perform(get("/api/claims/" + id + "/review").header("Authorization", manager))
            .andExpect(status().isOk()).andExpect(jsonPath("$.eligible", is(false)))
            .andExpect(jsonPath("$.citations[0]", is("P-2")));
        mvc.perform(post("/api/claims/" + id + "/decision").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/policy/answer").param("question", "What about an unrelated topic?")
            .header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.citations.length()", is(0)));
        mvc.perform(get("/api/claims/" + id).header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void asyncReceiptIsDurableIdempotentAndOnlyReviewedAfterExtraction() throws Exception {
        String employee = token("employee");
        String manager = token("manager");
        String receipt = "Merchant: Queue Cafe\nDate: 2026-10-03\nTotal: SGD 18.75";
        String body = json.writeValueAsString(new ClaimService.Submit("meal", 1875, receipt));
        String response = mvc.perform(post("/api/claims/async").header("Authorization", employee)
            .header("Idempotency-Key", "async-key-1").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.state", is("SUBMITTED")))
            .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("id").asText();
        mvc.perform(post("/api/claims/async").header("Authorization", employee)
            .header("Idempotency-Key", "async-key-1").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.id", is(id)));
        mvc.perform(post("/api/claims").header("Authorization", employee)
            .header("Idempotency-Key", "async-key-1").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isBadRequest());
        String changedBody = json.writeValueAsString(new ClaimService.Submit("meal", 1875,
            "Merchant: Other Cafe\nDate: 2026-10-03\nTotal: SGD 18.75"));
        mvc.perform(post("/api/claims/async").header("Authorization", employee)
            .header("Idempotency-Key", "async-key-1").contentType(MediaType.APPLICATION_JSON)
            .content(changedBody)).andExpect(status().isBadRequest());
        extraction.processOne(id);
        extraction.processOne(id);
        mvc.perform(get("/api/claims/" + id).header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("IN_REVIEW")))
            .andExpect(jsonPath("$.merchant", is("Queue Cafe")));
        org.junit.jupiter.api.Assertions.assertEquals(ExtractionJob.Status.DONE,
            jobs.findById(id).orElseThrow().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("", jobs.findById(id).orElseThrow().getReceiptText());
        mvc.perform(get("/api/claims/" + id + "/audit").header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(2)))
            .andExpect(jsonPath("$[1].action", is("EXTRACTED")));
    }

    @Test
    void invalidAsyncReceiptRequiresManagerCorrection() throws Exception {
        String employee = token("employee");
        String manager = token("manager");
        String body = json.writeValueAsString(new ClaimService.Submit("meal", 1900, "not a receipt"));
        String response = mvc.perform(post("/api/claims/async").header("Authorization", employee)
            .header("Idempotency-Key", "async-key-2").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("id").asText();
        extraction.processOne(id);
        mvc.perform(get("/api/claims/" + id).header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("NEEDS_HUMAN_CHECK")));
        org.junit.jupiter.api.Assertions.assertEquals(ExtractionJob.Status.DEAD,
            jobs.findById(id).orElseThrow().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("", jobs.findById(id).orElseThrow().getReceiptText());
        String correction = json.writeValueAsString(new ClaimController.ReceiptCheck(
            "Merchant: Manual Cafe\nDate: 2026-10-04\nTotal: SGD 19.00"));
        mvc.perform(post("/api/claims/" + id + "/receipt-check").header("Authorization", employee)
            .contentType(MediaType.APPLICATION_JSON).content(correction))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/claims/" + id + "/receipt-check").header("Authorization", manager)
            .contentType(MediaType.APPLICATION_JSON).content(correction))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("IN_REVIEW")));
        mvc.perform(get("/api/claims/" + id + "/audit").header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(3)))
            .andExpect(jsonPath("$[1].action", is("NEEDS_HUMAN_CHECK")))
            .andExpect(jsonPath("$[2].action", is("RECEIPT_CHECKED")));
    }

    @Test
    void threeTechnicalFailuresDeadLetterAndRequestHumanCheck() throws Exception {
        String employee = token("employee");
        String body = json.writeValueAsString(new ClaimService.Submit("meal", 2100,
            "Merchant: Retry Cafe\nDate: 2026-10-05\nTotal: SGD 21.00"));
        String response = mvc.perform(post("/api/claims/async").header("Authorization", employee)
            .header("Idempotency-Key", "async-key-3").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("id").asText();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                ExtractionJob job = jobs.findById(id).orElseThrow();
                org.springframework.test.util.ReflectionTestUtils.setField(job, "availableAt",
                    java.time.Instant.now().minusSeconds(1));
                jobs.save(job);
            }
            failures.recordFailure(id, new RuntimeException("transient failure"));
        }
        org.junit.jupiter.api.Assertions.assertEquals(ExtractionJob.Status.DEAD,
            jobs.findById(id).orElseThrow().getStatus());
        mvc.perform(get("/api/claims/" + id).header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("NEEDS_HUMAN_CHECK")));
        mvc.perform(get("/api/claims/" + id + "/audit").header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$[1].action", is("NEEDS_HUMAN_CHECK")));
    }

    @Test
    void concurrentSameKeySubmissionsReturnOneClaim() throws Exception {
        String key = "concurrent-" + java.util.UUID.randomUUID();
        ClaimService.Submit input = new ClaimService.Submit("meal", 1450,
            "Merchant: Race Cafe\nDate: 2026-10-06\nTotal: SGD 14.50");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Claim> call = () -> {
                gate.await();
                return submissions.submit("employee", key, input, true);
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            gate.countDown();
            Claim a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            Claim b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertEquals(a.getId(), b.getId());
            org.junit.jupiter.api.Assertions.assertEquals(a.getId(),
                claimRepository.findByIdempotencyKey(key).orElseThrow().getId());
            org.junit.jupiter.api.Assertions.assertEquals(ExtractionJob.Status.PENDING,
                jobs.findById(a.getId()).orElseThrow().getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentKeysWithOneReceiptGetClearDuplicateError() throws Exception {
        String nonce = java.util.UUID.randomUUID().toString();
        ClaimService.Submit input = new ClaimService.Submit("meal", 1550,
            "Merchant: Duplicate Race " + nonce + "\nDate: 2026-10-09\nTotal: SGD 15.50");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Claim> firstCall = () -> {
                gate.await();
                return submissions.submit("employee", "duplicate-race-a-" + nonce, input, false);
            };
            java.util.concurrent.Callable<Claim> secondCall = () -> {
                gate.await();
                return submissions.submit("employee", "duplicate-race-b-" + nonce, input, false);
            };
            var first = executor.submit(firstCall);
            var second = executor.submit(secondCall);
            gate.countDown();
            int successes = 0, duplicateErrors = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof IllegalArgumentException &&
                        cause.getMessage().contains("Duplicate receipt")) duplicateErrors++;
                    else throw error;
                }
            }
            org.junit.jupiter.api.Assertions.assertEquals(1, successes);
            org.junit.jupiter.api.Assertions.assertEquals(1, duplicateErrors);
            org.junit.jupiter.api.Assertions.assertEquals(1,
                claimRepository.findByReceiptHash(new ReceiptParser().parse(input.receiptText()).hash()).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void imageEndpointWithoutConfiguredOcrReturns503AndKeepsTextPath() throws Exception {
        String employee = token("employee");
        String key = "image-unconfigured-" + java.util.UUID.randomUUID();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/claims/image")
                .file(new org.springframework.mock.web.MockMultipartFile("receipt", "receipt.png", "image/png",
                    SyntheticReceiptImages.png(key)))
                .param("category", "meal").param("amountCents", "1000")
                .header("Authorization", employee).header("Idempotency-Key", key))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error", is("Image OCR is not configured on this server")));
        org.junit.jupiter.api.Assertions.assertFalse(claimRepository.findByIdempotencyKey(key).isPresent());
        mvc.perform(get("/api/claims/image/limits").header("Authorization", employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.maxBytes", is(5242880)))
            .andExpect(jsonPath("$.mediaTypes[0]", is("image/png")))
            .andExpect(jsonPath("$.mediaTypes[1]", is("image/jpeg")))
            .andExpect(jsonPath("$.ocrConfigured", is(false)));
        mvc.perform(get("/api/claims/image/limits").header("Authorization", token("manager")))
            .andExpect(status().isForbidden());
        String typed = json.writeValueAsString(new ClaimService.Submit("meal", 1000,
            "Merchant: Source Cafe " + key + "\nDate: 2026-10-10\nTotal: SGD 10.00"));
        mvc.perform(post("/api/claims").header("Authorization", employee).header("Idempotency-Key", key + "-text")
                .contentType(MediaType.APPLICATION_JSON).content(typed))
            .andExpect(status().isOk()).andExpect(jsonPath("$.receiptSource", is("TEXT")));
        mvc.perform(post("/api/claims").header("Authorization", employee).header("Idempotency-Key", key + "-none")
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"meal\",\"amountCents\":900}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.receiptSource", is("NONE")));
    }

    @Test
    void receiptHashHasDatabaseConstraintBeyondServiceCheck() {
        ReceiptParser.Parsed parsed = new ReceiptParser().parse(
            "Merchant: Unique Cafe\nDate: 2026-10-08\nTotal: SGD 13.25");
        claimRepository.saveAndFlush(new Claim("employee", "meal", 1325, parsed.merchant(),
            parsed.date(), parsed.hash(), "unique-hash-1"));
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () -> claimRepository.saveAndFlush(new Claim("employee", "meal", 1325,
                parsed.merchant(), parsed.date(), parsed.hash(), "unique-hash-2")));
    }
}
