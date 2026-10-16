package dev.claimflow;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "claimflow.extraction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExtractionPoller {
    private final ExtractionJobRepository jobs;
    private final ExtractionProcessor processor;
    private final ExtractionFailureService failures;

    public ExtractionPoller(ExtractionJobRepository jobs, ExtractionProcessor processor,
                            ExtractionFailureService failures) {
        this.jobs = jobs;
        this.processor = processor;
        this.failures = failures;
    }

    @Scheduled(fixedDelayString = "${claimflow.extraction.poll-ms:1000}")
    public void poll() {
        List<String> ids = jobs.findTop10ByStatusInAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
            List.of(ExtractionJob.Status.PENDING, ExtractionJob.Status.RETRY), Instant.now())
            .stream().map(ExtractionJob::getClaimId).toList();
        for (String id : ids) {
            try {
                processor.processOne(id);
            } catch (RuntimeException failure) {
                failures.recordFailure(id, failure);
            }
        }
    }
}
