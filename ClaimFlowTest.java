public final class ClaimFlowTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        ClaimFlow app = new ClaimFlow();
        ClaimFlow.Receipt r = ClaimFlow.extract("Merchant: Cafe\nDate: 2026-01-02\nTotal: SGD 12.50");
        check(r.cents() == 1250);
        ClaimFlow.Claim c = app.submit("employee", "meal", 1250, r, "key");
        check(c == app.submit("employee", "meal", 1250, r, "key"));
        boolean changed = false;
        try { app.submit("employee", "meal", 1300, r, "key"); } catch (IllegalArgumentException e) { changed = true; }
        check(changed);
        check(ClaimFlow.review(c, null, false).allowed());
        app.decide(c.id, true, "manager");
        check(c.state == ClaimFlow.State.APPROVED);
        boolean illegal = false;
        try { c.move(ClaimFlow.State.SUBMITTED); } catch (IllegalStateException e) { illegal = true; }
        check(illegal);
        ClaimFlow.Claim expensive = app.submit("employee", "meal", 7000, r, "key2");
        check(!ClaimFlow.review(expensive, null, false).allowed());
        boolean blocked = false;
        try { app.decide(expensive.id, true, "manager"); } catch (IllegalStateException e) { blocked = true; }
        check(blocked && expensive.state == ClaimFlow.State.IN_REVIEW);
        check(ClaimFlow.policyAnswer("taxi after 10pm").contains("[P-3]"));
        System.out.println("ClaimFlow tests passed");
    }
}
