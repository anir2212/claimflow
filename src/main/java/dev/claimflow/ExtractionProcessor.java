package dev.claimflow;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionProcessor {
    private final ExtractionJobRepository jobs;
    private final ClaimRepository claims;
    private final AuditEventRepository events;
    private final ReceiptParser parser;
    private final ClaimService service;

    public ExtractionProcessor(ExtractionJobRepository jobs, ClaimRepository claims,
                               AuditEventRepository events, ReceiptParser parser,
                               ClaimService service) {
        this.jobs = jobs;
        this.claims = claims;
        this.events = events;
        this.parser = parser;
        this.service = service;
    }

    @Transactional
    public void processOne(String claimId) {
        ExtractionJob job = jobs.lockById(claimId).orElseThrow(() ->
            new IllegalArgumentException("Unknown extraction job"));
        if (!job.due(Instant.now())) return;
        Claim claim = claims.lockById(claimId).orElseThrow(); // job row first, then claim row
        if (claim.getState() != ClaimState.SUBMITTED) {
            job.done();
            return;
        }
        try {
            ReceiptParser.Parsed receipt = parser.parse(job.getReceiptText());
            if (receipt.cents() != claim.getAmountCents()) {
                throw new IllegalArgumentException("Claim amount does not match receipt total");
            }
            service.checkDuplicateReceipt(receipt, claim.getAmountCents(), claimId);
            claim.applyReceipt(receipt);
            job.done();
            events.save(new AuditEvent(claimId, "extraction-worker", "EXTRACTED",
                "Text fields validated"));
        } catch (IllegalArgumentException invalid) {
            claim.move(ClaimState.NEEDS_HUMAN_CHECK);
            job.dead(invalid.getMessage());
            events.save(new AuditEvent(claimId, "extraction-worker", "NEEDS_HUMAN_CHECK",
                "Text validation failed"));
        }
    }
}
