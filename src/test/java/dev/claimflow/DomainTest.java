package dev.claimflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DomainTest {
    @Test
    void transitionGraphRejectsSkippingAndTerminalMoves() {
        Claim claim = new Claim("e1", "other", 100, null, null, null, "k1");
        assertThrows(IllegalStateException.class, () -> claim.move(ClaimState.APPROVED));
        claim.move(ClaimState.SUBMITTED);
        claim.move(ClaimState.EXTRACTED);
        claim.move(ClaimState.IN_REVIEW);
        claim.move(ClaimState.REJECTED);
        assertThrows(IllegalStateException.class, () -> claim.move(ClaimState.PAID));
    }

    @Test
    void parserChecksAmountAndPolicyIsDeterministic() {
        ReceiptParser.Parsed parsed = new ReceiptParser().parse(
            "Merchant: Cafe\nDate: 2026-10-02\nTotal: SGD 12.50");
        assertEquals(1250, parsed.cents());
        assertEquals(LocalDate.of(2026, 10, 2), parsed.date());
        assertThrows(IllegalArgumentException.class, () -> new ReceiptParser().parse("Total: $12.50"));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptParser().parse(
            "Merchant: Cafe\nDate: 2026-02-30\nTotal: SGD 12.50"));
        Claim meal = new Claim("e1", "meal", 7000, parsed.merchant(), parsed.date(), parsed.hash(), "k2");
        assertFalse(new PolicyService().review(meal, null, false).eligible());
        assertTrue(new PolicyService().answer("taxi after 10pm").citations().contains("P-3"));
        Claim taxi = new Claim("e1", "taxi", 2000, parsed.merchant(), parsed.date(), parsed.hash(), "k3");
        assertThrows(IllegalArgumentException.class, () -> new PolicyService().review(taxi, "later", false));
        assertTrue(new PolicyService().review(taxi, "22:01", false).eligible());
    }
}
