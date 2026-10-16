package dev.claimflow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policy")
public class PolicyController {
    private final PolicyService policy;
    public PolicyController(PolicyService policy) { this.policy = policy; }

    @GetMapping("/answer")
    public PolicyService.Answer answer(@RequestParam String question) {
        return policy.answer(question);
    }
}
