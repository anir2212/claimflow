package dev.claimflow;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionFailureService {
    private final ExtractionJobRepository jobs;
    private final ClaimRepository claims;
    private final AuditEventRepository events;

    public ExtractionFailureService(ExtractionJobRepository jobs, ClaimRepository claims,
                                    AuditEventRepository events) {
        this.jobs = jobs;
        this.claims = claims;
        this.events = events;
    }

    @Transactional
    public void recordFailure(String claimId, RuntimeException error) {
        ExtractionJob job = jobs.lockById(claimId).orElseThrow();
        if (!job.due(Instant.now())) return;
        job.retry(error.getClass().getSimpleName(), Instant.now());
        if (job.getStatus() == ExtractionJob.Status.DEAD) {
            Claim claim = claims.findById(claimId).orElseThrow();
            if (claim.getState() == ClaimState.SUBMITTED) {
                claim.move(ClaimState.NEEDS_HUMAN_CHECK);
                events.save(new AuditEvent(claimId, "extraction-worker", "NEEDS_HUMAN_CHECK",
                    "Extraction failed after three attempts"));
            }
        }
    }
}
