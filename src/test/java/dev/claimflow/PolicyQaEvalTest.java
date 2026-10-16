package dev.claimflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Development-set retrieval check, deliberately including paraphrases the baseline misses. */
class PolicyQaEvalTest {
    @Test
    void measureSingleClauseRetrievalAndAbstention() throws Exception {
        String csv;
        try (var input = getClass().getResourceAsStream("/policy_qa_cases.csv")) {
            csv = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        PolicyService policy = new PolicyService();
        int count = 0, relevant = 0, hits = 0, top3Hits = 0, abstentions = 0, correctAbstentions = 0;
        for (String line : csv.lines().skip(1).filter(row -> !row.isBlank()).toList()) {
            String[] fields = line.split(",", 2);
            PolicyService.Answer response = policy.answer(fields[1]);
            count++;
            if (fields[0].equals("NONE")) {
                abstentions++;
                if (response.citations().isEmpty()) correctAbstentions++;
            } else {
                relevant++;
                if (response.citations().contains(fields[0])) hits++;
                if (policy.retrieve(fields[1], 3).contains(fields[0])) top3Hits++;
            }
        }
        assertEquals(30, count);
        Map<String, Object> report = Map.of(
            "cases", count, "relevant_questions", relevant, "relevant_clause_hits_at_1", hits,
            "recall_at_1", (double) hits / relevant,
            "relevant_clause_hits_at_3", top3Hits,
            "recall_at_3", (double) top3Hits / relevant,
            "unanswerable_questions", abstentions, "correct_abstentions", correctAbstentions
        );
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/policy_qa_eval.json"),
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println("Policy QA baseline: " + report);
    }
}
