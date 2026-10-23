package dev.claimflow;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Application metrics for Prometheus. Counts are recorded only after the database commit that made
 * them true, so a rollback never inflates them. Each API instance keeps its own counters;
 * Prometheus adds them up across instances. Labels are fixed enums, never claim IDs.
 */
@Component
public class ClaimMetrics {
    /** Outcomes of {@code POST /api/claims/image}, one counter series each. */
    public enum UploadOutcome { QUEUED, REPLAYED, INVALID, TOO_LARGE, UNREADABLE, OCR_UNAVAILABLE }

    /** Histogram bucket bounds for time from claim creation to a manager decision. */
    static final Duration[] DECISION_BUCKETS = {
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1),
        Duration.ofHours(4), Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3), Duration.ofDays(7)
    };

    private final MeterRegistry meters;

    public ClaimMetrics(MeterRegistry meters) {
        this.meters = meters;
        // Register every series at zero so rate() and increase() see the first event.
        for (Map.Entry<ClaimState, Set<ClaimState>> edge : Claim.legalTransitions().entrySet()) {
            for (ClaimState to : edge.getValue()) transitions(edge.getKey(), to);
        }
        for (UploadOutcome outcome : UploadOutcome.values()) uploads(outcome);
        decisions("approved");
        decisions("rejected");
    }

    void transitionsWritten(Claim claim, List<Claim.Transition> moved) {
        afterCommit(() -> {
            for (Claim.Transition transition : moved) {
                transitions(transition.from(), transition.to()).increment();
                if (transition.to() == ClaimState.APPROVED || transition.to() == ClaimState.REJECTED) {
                    Duration waited = Duration.between(claim.getCreatedAt(), transition.at());
                    decisions(transition.to() == ClaimState.APPROVED ? "approved" : "rejected")
                        .record(waited.isNegative() ? Duration.ZERO : waited);
                }
            }
        });
    }

    public void upload(UploadOutcome outcome) { afterCommit(() -> uploads(outcome).increment()); }

    private Counter transitions(ClaimState from, ClaimState to) {
        return Counter.builder("claimflow.claim.transitions")
            .description("Committed claim state transitions")
            .tag("from", from.name()).tag("to", to.name())
            .register(meters);
    }

    private Counter uploads(UploadOutcome outcome) {
        return Counter.builder("claimflow.receipt.uploads")
            .description("Receipt image upload requests by outcome")
            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
            .register(meters);
    }

    private Timer decisions(String outcome) {
        return Timer.builder("claimflow.approval.latency")
            .description("Time from claim creation to a committed manager approval or rejection")
            .tag("outcome", outcome)
            .serviceLevelObjectives(DECISION_BUCKETS)
            .register(meters);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }
}
