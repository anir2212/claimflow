package dev.claimflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "claimflow.extraction.enabled=true",
    "claimflow.extraction.poll-ms=50",
    "spring.datasource.url=jdbc:h2:mem:claimflow-schedule;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class ExtractionSchedulingIntegrationTest {
    @Autowired ClaimSubmissionCoordinator submissions;
    @Autowired ClaimRepository claims;
    @Autowired ExtractionJobRepository jobs;

    @Test
    void scheduledWorkerEventuallyProcessesCommittedClaim() throws InterruptedException {
        Claim submitted = submissions.submit("employee", "scheduled-" + UUID.randomUUID(),
            new ClaimService.Submit("meal", 1650,
                "Merchant: Scheduled Cafe\nDate: 2026-10-07\nTotal: SGD 16.50"), true);
        assertEquals(ClaimState.SUBMITTED, submitted.getState());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        while (Instant.now().isBefore(deadline)) {
            if (claims.findById(submitted.getId()).orElseThrow().getState() == ClaimState.IN_REVIEW) {
                assertEquals(ExtractionJob.Status.DONE,
                    jobs.findById(submitted.getId()).orElseThrow().getStatus());
                return;
            }
            Thread.sleep(50);
        }
        fail("Scheduled worker did not process claim within eight seconds");
    }
}
