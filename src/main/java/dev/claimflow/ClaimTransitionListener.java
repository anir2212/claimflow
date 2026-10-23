package dev.claimflow;

import java.util.List;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;

import org.springframework.beans.factory.ObjectProvider;

/**
 * JPA callback that hands a claim's state changes to {@link ClaimMetrics} once its row has been
 * written. Hibernate builds this listener through Spring, so the metrics bean is injected. The
 * metrics bean defers counting until the surrounding transaction commits, so a rolled-back
 * change, such as the loser of an optimistic-lock race, is never counted.
 */
public class ClaimTransitionListener {
    private final ObjectProvider<ClaimMetrics> metrics;

    public ClaimTransitionListener(ObjectProvider<ClaimMetrics> metrics) { this.metrics = metrics; }

    @PostPersist
    @PostUpdate
    void written(Claim claim) {
        List<Claim.Transition> moved = claim.drainTransitions();
        ClaimMetrics target = metrics.getIfAvailable();
        if (!moved.isEmpty() && target != null) target.transitionsWritten(claim, moved);
    }
}
