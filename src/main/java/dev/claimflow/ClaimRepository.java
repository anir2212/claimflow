package dev.claimflow;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, String> {
    Optional<Claim> findByIdempotencyKey(String key);
    List<Claim> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);
    List<Claim> findByStateOrderByCreatedAtAsc(ClaimState state);
    List<Claim> findByReceiptHash(String receiptHash);

    /**
     * Reads a claim with {@code SELECT ... FOR UPDATE}. Every state change goes through this, so a
     * second request, on this or another API instance, waits for the first to commit and then
     * sees the new state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id")
    Optional<Claim> lockById(@Param("id") String id);
}
