import java.util.*;
import java.util.regex.*;

/** Local expense claim domain core. Money is integer cents. */
public final class ClaimFlow {
    enum State { DRAFT, SUBMITTED, EXTRACTED, IN_REVIEW, APPROVED, REJECTED, PAID }
    record Receipt(String merchant, String date, long cents, String currency) {}
    record Decision(boolean allowed, String clause, String explanation) {}
    static final Map<String, String> POLICY = Map.of(
        "P-1", "A receipt is required for all expenses of at least SGD 25.00.",
        "P-2", "Meals are capped at SGD 60.00 per claim.",
        "P-3", "Taxi rides after 22:00 are eligible; daytime taxi rides require a manager note.",
        "P-4", "Claims above SGD 200.00 require manager review."
    );

    static final class Claim {
        final String id, employee, category, idempotencyKey;
        final long cents;
        final Receipt receipt;
        State state = State.DRAFT;
        final List<String> audit = new ArrayList<>();
        Claim(String id, String employee, String category, long cents, Receipt receipt, String key) {
            if (cents <= 0 || !Set.of("meal", "taxi", "other").contains(category)) throw new IllegalArgumentException("Invalid claim");
            this.id = id; this.employee = employee; this.category = category; this.cents = cents; this.receipt = receipt; this.idempotencyKey = key;
            audit.add("created");
        }
        void move(State next) {
            Map<State, Set<State>> allowed = Map.of(
                State.DRAFT, Set.of(State.SUBMITTED), State.SUBMITTED, Set.of(State.EXTRACTED),
                State.EXTRACTED, Set.of(State.IN_REVIEW), State.IN_REVIEW, Set.of(State.APPROVED, State.REJECTED),
                State.APPROVED, Set.of(State.PAID));
            if (!allowed.getOrDefault(state, Set.of()).contains(next)) throw new IllegalStateException(state + " -> " + next);
            state = next; audit.add("state=" + next);
        }
    }

    final Map<String, Claim> claims = new LinkedHashMap<>();
    final Map<String, String> idempotency = new HashMap<>();

    Claim submit(String employee, String category, long cents, Receipt receipt, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key required");
        if (idempotency.containsKey(key)) {
            Claim prior = claims.get(idempotency.get(key));
            if (!prior.employee.equals(employee) || !prior.category.equals(category) || prior.cents != cents || !Objects.equals(prior.receipt, receipt))
                throw new IllegalArgumentException("idempotency key reused with different claim");
            return prior;
        }
        Claim claim = new Claim("C-" + (claims.size() + 1), employee, category, cents, receipt, key);
        claim.move(State.SUBMITTED); claim.move(State.EXTRACTED); claim.move(State.IN_REVIEW);
        claims.put(claim.id, claim); idempotency.put(key, claim.id);
        return claim;
    }

    static Decision review(Claim claim, String taxiTime, boolean managerNote) {
        if (claim.cents >= 2500 && claim.receipt == null) return new Decision(false, "P-1", "Receipt required");
        if (claim.category.equals("meal") && claim.cents > 6000) return new Decision(false, "P-2", "Meal cap exceeded");
        if (claim.category.equals("taxi") && (taxiTime == null || taxiTime.compareTo("22:00") < 0) && !managerNote)
            return new Decision(false, "P-3", "Manager note required for daytime taxi");
        return new Decision(true, claim.cents > 20000 ? "P-4" : "P-1", "Eligible for manager approval");
    }

    void decide(String id, boolean approve, String manager) {
        decide(id, approve, manager, null, false);
    }

    void decide(String id, boolean approve, String manager, String taxiTime, boolean managerNote) {
        Claim claim = Objects.requireNonNull(claims.get(id), "unknown claim");
        if (manager.equals(claim.employee)) throw new IllegalArgumentException("self approval denied");
        if (approve && !review(claim, taxiTime, managerNote).allowed()) throw new IllegalStateException("hard policy rule blocks approval");
        claim.move(approve ? State.APPROVED : State.REJECTED);
        claim.audit.add("manager=" + manager);
    }

    static Receipt extract(String text) {
        Matcher merchant = Pattern.compile("(?im)^merchant:\\s*(.+)$").matcher(text);
        Matcher date = Pattern.compile("(?im)^date:\\s*(\\d{4}-\\d{2}-\\d{2})$").matcher(text);
        Matcher total = Pattern.compile("(?im)^total:\\s*(SGD)\\s*(\\d+)\\.(\\d{2})$").matcher(text);
        if (!merchant.find() || !date.find() || !total.find()) throw new IllegalArgumentException("Receipt needs human check");
        return new Receipt(merchant.group(1).trim(), date.group(1), Long.parseLong(total.group(2))*100 + Long.parseLong(total.group(3)), total.group(1));
    }

    static String policyAnswer(String query) {
        String q = query.toLowerCase();
        if (q.contains("taxi")) return POLICY.get("P-3") + " [P-3]";
        if (q.contains("meal")) return POLICY.get("P-2") + " [P-2]";
        if (q.contains("receipt")) return POLICY.get("P-1") + " [P-1]";
        return "I cannot answer from the policy.";
    }

    public static void main(String[] args) {
        ClaimFlow app = new ClaimFlow();
        Receipt receipt = extract("Merchant: Example Cafe\nDate: 2026-10-01\nTotal: SGD 12.50");
        Claim claim = app.submit("employee-1", "meal", receipt.cents(), receipt, "demo-1");
        System.out.println("Claim " + claim.id + " " + claim.state + ": " + review(claim, null, false));
        app.decide(claim.id, true, "manager-1");
        System.out.println("After approval: " + claim.state + "; " + policyAnswer("meal limit?"));
    }
}
