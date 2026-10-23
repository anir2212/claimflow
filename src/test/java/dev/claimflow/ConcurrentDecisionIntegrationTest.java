package dev.claimflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * Races manager decisions and finance payments on one claim from several threads, the in-process
 * counterpart of scripts/multiworker_check.py (docs/MULTI_WORKER_PROTOCOL.md). Exactly one request
 * may change the claim; every other one must end as a conflict that the API maps to HTTP 409.
 */
@SpringBootTest(properties = {
    "claimflow.extraction.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:claimflow-decision-race;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class ConcurrentDecisionIntegrationTest {
    private static final int CLAIMS = 12;
    private static final int RACERS = 4;

    @Autowired ClaimSubmissionCoordinator submissions;
    @Autowired ClaimService service;
    @Autowired ClaimRepository claims;
    @Autowired AuditEventRepository events;
    @Autowired ApiErrors errors;

    private final ExecutorService pool = Executors.newFixedThreadPool(RACERS);

    @AfterEach
    void stop() { pool.shutdownNow(); }

    private enum Outcome { CHANGED, CONFLICT }

    private String claimInReview(int index, String nonce) {
        long cents = 1100 + index;
        String text = "Merchant: Race Decision " + nonce + " " + index + "\nDate: 2026-10-24\nTotal: SGD "
            + (cents / 100) + "." + String.format("%02d", cents % 100);
        return submissions.submit("employee", "race-" + nonce + "-" + index,
            new ClaimService.Submit("meal", cents, text), false).getId();
    }

    /** Starts every call at one barrier; an exception other than a mapped conflict fails the test. */
    private List<Outcome> race(List<Callable<Claim>> calls) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(calls.size());
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Claim> call : calls) {
            futures.add(pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                try {
                    call.call();
                    return Outcome.CHANGED;
                } catch (IllegalStateException conflict) {
                    assertEquals(409, errors.conflict(conflict).getStatusCode().value());
                    return Outcome.CONFLICT;
                } catch (ConcurrencyFailureException conflict) {
                    assertEquals(409, errors.concurrentChange(conflict).getStatusCode().value());
                    return Outcome.CONFLICT;
                }
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
        return outcomes;
    }

    private long auditCount(String id, String... actions) {
        List<String> wanted = List.of(actions);
        return events.findByClaimIdOrderByOccurredAtAsc(id).stream()
            .filter(event -> wanted.contains(event.getAction())).count();
    }

    @Test
    void concurrentApproveAndRejectApplyExactlyOneDecision() throws Exception {
        String nonce = UUID.randomUUID().toString();
        for (int i = 0; i < CLAIMS; i++) {
            String id = claimInReview(i, nonce);
            boolean[] approve = {true, false, true, false};
            List<Callable<Claim>> calls = new ArrayList<>();
            for (boolean choice : approve) {
                calls.add(() -> service.decide(id, "manager", new ClaimService.Decision(choice, null, false)));
            }
            List<Outcome> outcomes = race(calls);
            int winner = outcomes.indexOf(Outcome.CHANGED);
            assertEquals(1, outcomes.stream().filter(o -> o == Outcome.CHANGED).count(), "claim " + i + ": " + outcomes);
            assertTrue(winner >= 0);
            String expected = approve[winner] ? "APPROVED" : "REJECTED";
            assertEquals(expected, claims.findById(id).orElseThrow().getState().name(), "claim " + i);
            assertEquals(1, auditCount(id, "APPROVED", "REJECTED"), "claim " + i);
        }
    }

    @Test
    void concurrentPaymentsPayExactlyOnce() throws Exception {
        String nonce = UUID.randomUUID().toString();
        for (int i = 0; i < CLAIMS; i++) {
            String id = claimInReview(i, nonce);
            service.decide(id, "manager", new ClaimService.Decision(true, null, false));
            List<Callable<Claim>> calls = new ArrayList<>();
            for (int r = 0; r < RACERS; r++) calls.add(() -> service.markPaid(id, "finance"));
            List<Outcome> outcomes = race(calls);
            assertEquals(1, outcomes.stream().filter(o -> o == Outcome.CHANGED).count(), "claim " + i + ": " + outcomes);
            assertEquals("PAID", claims.findById(id).orElseThrow().getState().name(), "claim " + i);
            assertEquals(1, auditCount(id, "PAID"), "claim " + i);
        }
    }

    @Test
    void staleUpdateFailsOnVersionAndMapsTo409() throws Exception {
        String id = claimInReview(99, UUID.randomUUID().toString());
        Claim stale = claims.findById(id).orElseThrow();
        service.decide(id, "manager", new ClaimService.Decision(false, null, false));
        stale.move(ClaimState.APPROVED);
        ConcurrencyFailureException conflict = org.junit.jupiter.api.Assertions.assertThrows(
            ConcurrencyFailureException.class, () -> claims.saveAndFlush(stale));
        assertEquals(409, errors.concurrentChange(conflict).getStatusCode().value());
        assertEquals("REJECTED", claims.findById(id).orElseThrow().getState().name());
        assertEquals(1, auditCount(id, "APPROVED", "REJECTED"));
    }
}
