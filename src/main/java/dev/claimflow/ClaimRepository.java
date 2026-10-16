package dev.claimflow;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, String> {
    Optional<Claim> findByIdempotencyKey(String key);
    List<Claim> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);
    List<Claim> findByStateOrderByCreatedAtAsc(ClaimState state);
    List<Claim> findByReceiptHash(String receiptHash);
}
