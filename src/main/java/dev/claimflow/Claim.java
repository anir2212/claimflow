package dev.claimflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "claims")
public class Claim {
    private static final Map<ClaimState, Set<ClaimState>> TRANSITIONS = Map.of(
        ClaimState.DRAFT, Set.of(ClaimState.SUBMITTED),
        ClaimState.SUBMITTED, Set.of(ClaimState.EXTRACTED, ClaimState.NEEDS_HUMAN_CHECK),
        ClaimState.NEEDS_HUMAN_CHECK, Set.of(ClaimState.EXTRACTED),
        ClaimState.EXTRACTED, Set.of(ClaimState.IN_REVIEW),
        ClaimState.IN_REVIEW, Set.of(ClaimState.APPROVED, ClaimState.REJECTED),
        ClaimState.APPROVED, Set.of(ClaimState.PAID)
    );

    @Id private String id;
    @Column(name = "employee_id", nullable = false) private String employeeId;
    @Column(nullable = false) private String category;
    @Column(name = "amount_cents", nullable = false) private long amountCents;
    private String merchant;
    @Column(name = "expense_date") private LocalDate expenseDate;
    @Column(name = "receipt_hash") private String receiptHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ClaimState state;
    @Column(name = "idempotency_key", nullable = false, unique = true) private String idempotencyKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

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
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void move(ClaimState next) {
        if (!TRANSITIONS.getOrDefault(state, Set.of()).contains(next)) {
            throw new IllegalStateException("Illegal transition " + state + " -> " + next);
        }
        this.state = next;
        this.updatedAt = Instant.now();
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

    public String getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public String getCategory() { return category; }
    public long getAmountCents() { return amountCents; }
    public String getMerchant() { return merchant; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public String getReceiptHash() { return receiptHash; }
    public ClaimState getState() { return state; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
