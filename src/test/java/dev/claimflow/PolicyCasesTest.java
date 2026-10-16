package dev.claimflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class PolicyCasesTest {
    private final PolicyService policy = new PolicyService();

    @ParameterizedTest(name = "case {0}: {1} {2} cents")
    @CsvFileSource(resources = "/review_cases.csv", numLinesToSkip = 1)
    void labelledHardRuleCase(String id, String category, long cents, boolean receipt,
                              String taxiTime, boolean managerNote, boolean eligible,
                              String citation) {
        Claim claim = new Claim("employee-1", category, cents, receipt ? "Merchant" : null,
            null, receipt ? "receipt-hash" : null, "case-" + id);
        PolicyService.Review result = policy.review(claim, taxiTime.equals("-") ? null : taxiTime,
                                                     managerNote);
        assertEquals(eligible, result.eligible(), id);
        if (eligible) assertTrue(result.violations().isEmpty(), id);
        else assertFalse(result.violations().isEmpty(), id);
        if (!citation.equals("none")) assertTrue(result.citations().contains(citation), id);
    }
}
