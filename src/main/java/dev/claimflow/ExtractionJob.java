package dev.claimflow;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "extraction_jobs")
public class ExtractionJob {
    public enum Status { PENDING, RETRY, DONE, DEAD }

    @Id @Column(name = "claim_id") private String claimId;
    @Column(name = "receipt_text", length = 10000, nullable = false) private String receiptText;
    @Column(name = "input_hash", length = 64, nullable = false) private String inputHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(nullable = false) private int attempts;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "last_error", length = 500) private String lastError;

    protected ExtractionJob() {}

    public ExtractionJob(String claimId, String receiptText, String inputHash) {
        this.claimId = claimId;
        this.receiptText = receiptText;
        this.inputHash = inputHash;
        this.status = Status.PENDING;
        this.availableAt = Instant.now();
    }

    public String getClaimId() { return claimId; }
    public String getReceiptText() { return receiptText; }
    public String getInputHash() { return inputHash; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getAvailableAt() { return availableAt; }
    public String getLastError() { return lastError; }

    public boolean due(Instant now) {
        return (status == Status.PENDING || status == Status.RETRY) && !availableAt.isAfter(now);
    }

    public void done() {
        status = Status.DONE;
        receiptText = "";
        lastError = null;
    }

    public void dead(String reason) {
        status = Status.DEAD;
        receiptText = "";
        lastError = truncate(reason);
    }

    public void retry(String reason, Instant now) {
        attempts++;
        lastError = truncate(reason);
        if (attempts >= 3) {
            status = Status.DEAD;
            receiptText = "";
        } else {
            status = Status.RETRY;
            availableAt = now.plusSeconds(1L << attempts);
        }
    }

    private String truncate(String value) {
        return value == null ? "Unknown failure" : value.substring(0, Math.min(500, value.length()));
    }
}
