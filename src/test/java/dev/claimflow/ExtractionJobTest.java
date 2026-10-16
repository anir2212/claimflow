package dev.claimflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ExtractionJobTest {
    @Test
    void transientFailuresBackOffAndEventuallyDeadLetter() {
        ExtractionJob job = new ExtractionJob("claim-id", "receipt", "hash");
        Instant now = Instant.now();
        job.retry("network", now);
        assertEquals(ExtractionJob.Status.RETRY, job.getStatus());
        assertEquals(now.plusSeconds(2), job.getAvailableAt());
        assertFalse(job.due(now));
        assertTrue(job.due(now.plusSeconds(2)));
        job.retry("network", now.plusSeconds(2));
        assertEquals(now.plusSeconds(6), job.getAvailableAt());
        job.retry("network", now.plusSeconds(6));
        assertEquals(ExtractionJob.Status.DEAD, job.getStatus());
        assertEquals(3, job.getAttempts());
        assertEquals("", job.getReceiptText());
    }
}
