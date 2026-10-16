package dev.claimflow;

import java.util.List;
import java.util.Map;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Service;

@Service
public class PolicyService {
    private static final Map<String, String> CLAUSES = Map.of(
        "P-1", "A receipt is required for expenses of at least SGD 25.00.",
        "P-2", "A meal claim cannot exceed SGD 60.00.",
        "P-3", "A taxi after 22:00 is eligible. An earlier taxi requires a manager note.",
        "P-4", "A claim above SGD 200.00 requires manager review."
    );
    private static final PolicyRetriever RETRIEVER = new PolicyRetriever(CLAUSES);

    public record Review(boolean eligible, List<String> violations, List<String> citations) {}
    public record Answer(String answer, List<String> citations) {}

    public Review review(Claim claim, String taxiTime, boolean managerNote) {
        java.util.ArrayList<String> violations = new java.util.ArrayList<>();
        java.util.ArrayList<String> citations = new java.util.ArrayList<>();
        if (claim.getAmountCents() >= 2500 && claim.getReceiptHash() == null) {
            violations.add("Receipt required"); citations.add("P-1");
        }
        if (claim.getCategory().equals("meal") && claim.getAmountCents() > 6000) {
            violations.add("Meal cap exceeded"); citations.add("P-2");
        }
        LocalTime taxiAt = null;
        if (claim.getCategory().equals("taxi") && taxiTime != null) {
            try { taxiAt = LocalTime.parse(taxiTime); }
            catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("Taxi time must be HH:mm", invalid);
            }
        }
        if (claim.getCategory().equals("taxi") &&
            (taxiAt == null || taxiAt.isBefore(LocalTime.of(22, 0))) && !managerNote) {
            violations.add("Manager note required for daytime taxi"); citations.add("P-3");
        }
        if (claim.getAmountCents() > 20000) citations.add("P-4");
        return new Review(violations.isEmpty(), List.copyOf(violations), List.copyOf(citations));
    }

    public Answer answer(String question) {
        List<String> hits = RETRIEVER.retrieve(question, 1);
        if (hits.isEmpty()) return new Answer("I cannot answer from the policy.", List.of());
        String clause = hits.getFirst();
        return new Answer(CLAUSES.get(clause) + " [" + clause + "]", List.of(clause));
    }

    public List<String> retrieve(String question, int limit) {
        return RETRIEVER.retrieve(question, limit);
    }
}
