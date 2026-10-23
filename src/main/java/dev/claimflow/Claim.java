package dev.claimflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "claims")
@EntityListeners(ClaimTransitionListener.class)
public class Claim implements Persistable<String> {
    private static final Map<ClaimState, Set<ClaimState>> TRANSITIONS = Map.of(
        ClaimState.DRAFT, Set.of(ClaimState.SUBMITTED),
        ClaimState.SUBMITTED, Set.of(ClaimState.EXTRACTED, ClaimState.NEEDS_HUMAN_CHECK),
        ClaimState.NEEDS_HUMAN_CHECK, Set.of(ClaimState.EXTRACTED),
        ClaimState.EXTRACTED, Set.of(ClaimState.IN_REVIEW),
        ClaimState.IN_REVIEW, Set.of(ClaimState.APPROVED, ClaimState.REJECTED),
        ClaimState.APPROVED, Set.of(ClaimState.PAID)
    );

    /** One state change, kept in memory until the row is written so metrics can count it. */
    public record Transition(ClaimState from, ClaimState to, Instant at) {}

    /** How the receipt reached the server. Image bytes themselves are never stored. */
    public enum ReceiptSource { NONE, TEXT, IMAGE }

    @Id private String id;
    @Column(name = "employee_id", nullable = false) private String employeeId;
    @Column(nullable = false) private String category;
    @Column(name = "amount_cents", nullable = false) private long amountCents;
    private String merchant;
    @Column(name = "expense_date") private LocalDate expenseDate;
    @Column(name = "receipt_hash") private String receiptHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ClaimState state;
    @Enumerated(EnumType.STRING) @Column(name = "receipt_source", nullable = false)
    private ReceiptSource receiptSource;
    @Column(name = "idempotency_key", nullable = false, unique = true) private String idempotencyKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    /** Optimistic-lock backstop: an update based on a stale read fails instead of overwriting. */
    @Version @Column(nullable = false) private long version;
    @Transient private final List<Transition> unflushedTransitions = new ArrayList<>();
    @Transient private boolean stored;

    protected Claim() {}

    public Claim(String employeeId, String category, long amountCents, String merchant,
                 LocalDate expenseDate, String receiptHash, String idempotencyKey) {
        if (amountCents <= 0 || !Set.of("meal", "taxi", "lodging", "other").contains(category)) {
            throw new IllegalArgumentException("Invalid amount or category");
        }
        this.id = UUID.randomUUID().toString();
        this.employeeId = employeeId;
        this.category = category;
        this.amountCents = amountCents;
        this.merchant = merchant;
        this.expenseDate = expenseDate;
        this.receiptHash = receiptHash;
        this.idempotencyKey = idempotencyKey;
        this.state = ClaimState.DRAFT;
        this.receiptSource = receiptHash == null ? ReceiptSource.NONE : ReceiptSource.TEXT;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void move(ClaimState next) {
        if (!TRANSITIONS.getOrDefault(state, Set.of()).contains(next)) {
            throw new IllegalStateException("Illegal transition " + state + " -> " + next);
        }
        Instant now = Instant.now();
        unflushedTransitions.add(new Transition(state, next, now));
        this.state = next;
        this.updatedAt = now;
    }

    public void applyReceipt(ReceiptParser.Parsed receipt) {
        if (state != ClaimState.SUBMITTED && state != ClaimState.NEEDS_HUMAN_CHECK) {
            throw new IllegalStateException("Receipt cannot be applied in state " + state);
        }
        if (receipt.cents() != amountCents) {
            throw new IllegalArgumentException("Claim amount does not match receipt total");
        }
        merchant = receipt.merchant();
        expenseDate = receipt.date();
        receiptHash = receipt.hash();
        move(ClaimState.EXTRACTED);
        move(ClaimState.IN_REVIEW);
    }

    /** The legal edges of the workflow graph, used to pre-register one counter per edge. */
    static Map<ClaimState, Set<ClaimState>> legalTransitions() { return TRANSITIONS; }

    /** Returns and forgets the transitions made since the last database write of this claim. */
    List<Transition> drainTransitions() {
        List<Transition> drained = List.copyOf(unflushedTransitions);
        unflushedTransitions.clear();
        return drained;
    }

    /** Set by the service when a queued claim's receipt arrived as text or as an uploaded image. */
    void receivedReceiptAs(ReceiptSource source) {
        if (state != ClaimState.DRAFT) throw new IllegalStateException("Receipt source is fixed after submission");
        this.receiptSource = source;
    }

    /**
     * The ID is assigned in the constructor, so Spring Data cannot tell a new claim from its ID.
     * Saying so here makes {@code save} persist this instance instead of merging a copy, which
     * would lose the in-memory transition list that metrics read.
     */
    @Override @JsonIgnore
    public boolean isNew() { return !stored; }

    @PostLoad @PostPersist
    void markStored() { stored = true; }

    @Override
    public String getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public String getCategory() { return category; }
    public long getAmountCents() { return amountCents; }
    public String getMerchant() { return merchant; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public String getReceiptHash() { return receiptHash; }
    public ClaimState getState() { return state; }
    public ReceiptSource getReceiptSource() { return receiptSource; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
