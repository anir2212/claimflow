package dev.claimflow;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {
    public record Submit(@NotBlank String category, @Positive long amountCents, String receiptText) {}
    public record Decision(@jakarta.validation.constraints.NotNull Boolean approve,
                           String taxiTime, boolean managerNote) {}
    public record Stats(long claimCount, long totalCents, Map<String, Long> byCategoryCents,
                        Map<ClaimState, Long> byStateCount) {}

    private final ClaimRepository claims;
    private final AuditEventRepository events;
    private final ReceiptParser parser;
    private final PolicyService policy;
    private final ExtractionJobRepository jobs;

    public ClaimService(ClaimRepository claims, AuditEventRepository events,
                        ReceiptParser parser, PolicyService policy, ExtractionJobRepository jobs) {
        this.claims = claims;
        this.events = events;
        this.parser = parser;
        this.policy = policy;
        this.jobs = jobs;
    }

    @Transactional
    public Claim submit(String employee, String key, Submit input) {
        validateKey(key);
        var existing = claims.findByIdempotencyKey(key);
        ReceiptParser.Parsed receipt = input.receiptText() == null || input.receiptText().isBlank()
            ? null : parser.parse(input.receiptText());
        if (existing.isPresent()) {
            return replay(employee, key, input, false);
        }
        if (receipt != null && receipt.cents() != input.amountCents()) {
            throw new IllegalArgumentException("Claim amount does not match receipt total");
        }
        if (receipt != null) {
            checkDuplicateReceipt(receipt, input.amountCents(), null);
        }
        Claim claim = new Claim(employee, input.category(), input.amountCents(),
            receipt == null ? null : receipt.merchant(),
            receipt == null ? null : receipt.date(),
            receipt == null ? null : receipt.hash(), key);
        claim.move(ClaimState.SUBMITTED);
        claim.move(ClaimState.EXTRACTED); // text baseline, or no-receipt path, completes synchronously
        claim.move(ClaimState.IN_REVIEW);
        claims.save(claim);
        events.save(new AuditEvent(claim.getId(), employee, "SUBMITTED", "Text receipt baseline"));
        return claim;
    }

    @Transactional
    public Claim submitAsync(String employee, String key, Submit input) {
        validateKey(key);
        if (input.receiptText() == null || input.receiptText().isBlank() ||
            input.receiptText().length() > 10000) {
            throw new IllegalArgumentException("Async receipt text is required (max 10000 characters)");
        }
        String inputHash = hash(input.receiptText());
        var existing = claims.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(employee, key, input, true);
        }
        Claim claim = new Claim(employee, input.category(), input.amountCents(),
            null, null, null, key);
        claim.move(ClaimState.SUBMITTED);
        claims.save(claim);
        jobs.save(new ExtractionJob(claim.getId(), input.receiptText(), inputHash));
        events.save(new AuditEvent(claim.getId(), employee, "SUBMITTED", "Async text extraction queued"));
        return claim;
    }

    @Transactional(readOnly = true)
    public Claim replay(String employee, String key, Submit input, boolean async) {
        validateKey(key);
        Claim prior = claims.findByIdempotencyKey(key).orElse(null);
        if (prior == null) return null;
        if (!prior.getEmployeeId().equals(employee) ||
            !prior.getCategory().equals(input.category()) ||
            prior.getAmountCents() != input.amountCents()) {
            throw new IllegalArgumentException("Idempotency key reused with different claim");
        }
        if (async) {
            ExtractionJob job = jobs.findById(prior.getId()).orElseThrow(() ->
                new IllegalArgumentException("Idempotency key reused with a synchronous claim"));
            if (input.receiptText() == null || !job.getInputHash().equals(hash(input.receiptText()))) {
                throw new IllegalArgumentException("Idempotency key reused with different claim");
            }
        } else {
            if (jobs.existsById(prior.getId())) {
                throw new IllegalArgumentException("Idempotency key reused with an async claim");
            }
            ReceiptParser.Parsed receipt = input.receiptText() == null || input.receiptText().isBlank()
                ? null : parser.parse(input.receiptText());
            if (!Objects.equals(prior.getReceiptHash(), receipt == null ? null : receipt.hash())) {
                throw new IllegalArgumentException("Idempotency key reused with different claim");
            }
        }
        return prior;
    }

    @Transactional
    public Claim resolveReceipt(String id, String manager, String receiptText) {
        Claim claim = get(id);
        if (claim.getState() != ClaimState.NEEDS_HUMAN_CHECK) {
            throw new IllegalStateException("Claim is not awaiting receipt check");
        }
        ReceiptParser.Parsed receipt = parser.parse(receiptText);
        checkDuplicateReceipt(receipt, claim.getAmountCents(), id);
        claim.applyReceipt(receipt);
        events.save(new AuditEvent(id, manager, "RECEIPT_CHECKED", "Manager supplied corrected transcription"));
        return claim;
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new IllegalArgumentException("Idempotency-Key header is required (max 120 characters)");
        }
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void checkDuplicateReceipt(ReceiptParser.Parsed receipt, long amountCents, String ownId) {
        boolean duplicate = claims.findByReceiptHash(receipt.hash()).stream().anyMatch(prior ->
            !prior.getId().equals(ownId) && prior.getAmountCents() == amountCents &&
            prior.getMerchant().equalsIgnoreCase(receipt.merchant()) &&
            prior.getExpenseDate().equals(receipt.date()));
        if (duplicate) throw new IllegalArgumentException("Duplicate receipt and expense details");
    }

    @Transactional(readOnly = true)
    public void classifySynchronousReceiptCollision(Submit input) {
        if (input.receiptText() != null && !input.receiptText().isBlank()) {
            checkDuplicateReceipt(parser.parse(input.receiptText()), input.amountCents(), null);
        }
    }

    @Transactional(readOnly = true)
    public Claim get(String id) {
        return claims.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown claim"));
    }

    @Transactional(readOnly = true)
    public List<Claim> mine(String employee) { return claims.findByEmployeeIdOrderByCreatedAtDesc(employee); }

    @Transactional(readOnly = true)
    public List<Claim> queue() { return claims.findByStateOrderByCreatedAtAsc(ClaimState.IN_REVIEW); }

    @Transactional(readOnly = true)
    public List<Claim> needsHumanCheck() {
        return claims.findByStateOrderByCreatedAtAsc(ClaimState.NEEDS_HUMAN_CHECK);
    }

    @Transactional(readOnly = true)
    public List<Claim> approved() { return claims.findByStateOrderByCreatedAtAsc(ClaimState.APPROVED); }

    @Transactional(readOnly = true)
    public Stats stats() {
        List<Claim> all = claims.findAll();
        Map<String, Long> categories = new HashMap<>();
        Map<ClaimState, Long> states = new HashMap<>();
        long total = 0;
        for (Claim claim : all) {
            total += claim.getAmountCents();
            categories.merge(claim.getCategory(), claim.getAmountCents(), Long::sum);
            states.merge(claim.getState(), 1L, Long::sum);
        }
        return new Stats(all.size(), total, Map.copyOf(categories), Map.copyOf(states));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> audit(String id) { get(id); return events.findByClaimIdOrderByOccurredAtAsc(id); }

    @Transactional
    public Claim decide(String id, String manager, Decision decision) {
        Claim claim = get(id);
        if (claim.getEmployeeId().equals(manager)) throw new IllegalArgumentException("Self approval denied");
        if (decision.approve() && !policy.review(claim, decision.taxiTime(), decision.managerNote()).eligible()) {
            throw new IllegalStateException("Hard policy rule blocks approval");
        }
        claim.move(decision.approve() ? ClaimState.APPROVED : ClaimState.REJECTED);
        events.save(new AuditEvent(id, manager, decision.approve() ? "APPROVED" : "REJECTED",
            "Manager decision"));
        return claim;
    }

    @Transactional
    public Claim markPaid(String id, String finance) {
        Claim claim = get(id);
        claim.move(ClaimState.PAID);
        events.save(new AuditEvent(id, finance, "PAID", "Finance settlement"));
        return claim;
    }
}
