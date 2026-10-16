package dev.claimflow;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ExtractionMetrics {
    public ExtractionMetrics(MeterRegistry meters, ExtractionJobRepository jobs) {
        for (ExtractionJob.Status status : ExtractionJob.Status.values()) {
            Gauge.builder("claimflow.extraction.jobs", jobs,
                    repository -> repository.countByStatus(status))
                .description("Persisted extraction jobs by state")
                .tag("status", status.name())
                .register(meters);
        }
    }
}
