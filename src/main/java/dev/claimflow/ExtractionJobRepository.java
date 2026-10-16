package dev.claimflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtractionJobRepository extends JpaRepository<ExtractionJob, String> {
    long countByStatus(ExtractionJob.Status status);

    List<ExtractionJob> findTop10ByStatusInAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
        List<ExtractionJob.Status> statuses, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ExtractionJob j where j.claimId = :id")
    Optional<ExtractionJob> lockById(@Param("id") String id);
}
