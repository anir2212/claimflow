package dev.claimflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Small, inspectable lexical retriever for the four fictional policy clauses. */
public class PolicyRetriever {
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Set<String> STOP = Set.of(
        "a", "an", "and", "are", "at", "be", "can", "claim", "do", "does", "for", "from",
        "how", "i", "in", "is", "it", "my", "need", "of", "on", "or", "policy", "sgd",
        "the", "to", "what", "when", "where", "with");
    private static final Map<String, Set<String>> ALIASES = Map.of(
        "P-1", Set.of("receipt", "receipts", "proof", "purchase", "documentation", "document",
            "slip", "invoice", "invoices", "evidence"),
        "P-2", Set.of("meal", "meals", "lunch", "dinner", "breakfast", "food", "dining",
            "restaurant", "cap", "ceiling"),
        "P-3", Set.of("taxi", "taxis", "cab", "cabs", "ride", "hail", "transport", "fare",
            "night", "daytime", "late", "22", "23"),
        "P-4", Set.of("approval", "approve", "threshold", "review", "manager", "200", "above")
    );

    private final Map<String, String> clauses;

    public PolicyRetriever(Map<String, String> clauses) { this.clauses = Map.copyOf(clauses); }

    public List<String> retrieve(String question, int limit) {
        if (question == null || question.isBlank() || limit <= 0) return List.of();
        Set<String> terms = tokens(question);
        List<Hit> hits = new ArrayList<>();
        for (var entry : clauses.entrySet()) {
            int score = 0;
            Set<String> content = tokens(entry.getValue());
            for (String term : terms) {
                if (ALIASES.getOrDefault(entry.getKey(), Set.of()).contains(term)) score += 3;
                else if (content.contains(term)) score++;
            }
            if (score > 0) hits.add(new Hit(entry.getKey(), score));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(Hit::id));
        return hits.stream().limit(limit).map(Hit::id).toList();
    }

    private Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP.contains(token)) result.add(token);
        }
        return result;
    }

    private record Hit(String id, int score) {}
}
