package dev.claimflow;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id private String id;
    @Column(name = "claim_id", nullable = false) private String claimId;
    @Column(nullable = false) private String actor;
    @Column(nullable = false) private String action;
    private String detail;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;

    protected AuditEvent() {}

    public AuditEvent(String claimId, String actor, String action, String detail) {
        this.id = UUID.randomUUID().toString();
        this.claimId = claimId;
        this.actor = actor;
        this.action = action;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public String getId() { return id; }
    public String getClaimId() { return claimId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
