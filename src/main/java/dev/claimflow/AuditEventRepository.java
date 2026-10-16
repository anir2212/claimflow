package dev.claimflow;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
    List<AuditEvent> findByClaimIdOrderByOccurredAtAsc(String claimId);
}
