package dev.claimflow;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Resolves a unique-key race only after the first submission transaction has ended. */
@Service
public class ClaimSubmissionCoordinator {
    private final ClaimService service;

    public ClaimSubmissionCoordinator(ClaimService service) { this.service = service; }

    public Claim submit(String employee, String key, ClaimService.Submit input, boolean async) {
        try {
            return async ? service.submitAsync(employee, key, input) : service.submit(employee, key, input);
        } catch (DataIntegrityViolationException collision) {
            Claim prior = service.replay(employee, key, input, async);
            if (prior != null) return prior;
            if (!async) service.classifySynchronousReceiptCollision(input);
            throw collision;
        }
    }
}
