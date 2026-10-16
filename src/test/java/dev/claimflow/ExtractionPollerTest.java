package dev.claimflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExtractionPollerTest {
    @Test
    void technicalProcessorFailureIsPassedToIndependentFailureRecorder() {
        ExtractionJobRepository jobs = mock(ExtractionJobRepository.class);
        ExtractionProcessor processor = mock(ExtractionProcessor.class);
        ExtractionFailureService failures = mock(ExtractionFailureService.class);
        ExtractionJob job = new ExtractionJob("claim-id", "receipt", "hash");
        when(jobs.findTop10ByStatusInAndAvailableAtLessThanEqualOrderByAvailableAtAsc(any(), any()))
            .thenReturn(List.of(job));
        doThrow(new RuntimeException("database unavailable")).when(processor).processOne("claim-id");

        new ExtractionPoller(jobs, processor, failures).poll();

        verify(processor).processOne("claim-id");
        verify(failures).recordFailure(eq("claim-id"), any(RuntimeException.class));
    }
}
