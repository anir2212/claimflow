package dev.claimflow;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JwtService jwt;
    private final String demoPassword;

    public record Credentials(String username, String password) {}

    public AuthController(JwtService jwt,
                          @Value("${claimflow.demo-password:local-only}") String demoPassword) {
        this.jwt = jwt;
        this.demoPassword = demoPassword;
    }

    @PostMapping("/token")
    @SecurityRequirements
    public Map<String, String> token(@RequestBody Credentials credentials) {
        String role = switch (credentials.username() == null ? "" : credentials.username()) {
            case "employee" -> "EMPLOYEE";
            case "manager" -> "MANAGER";
            case "finance" -> "FINANCE";
            default -> null;
        };
        if (role == null || !demoPassword.equals(credentials.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid demo credentials");
        }
        return Map.of("access_token", jwt.issue(credentials.username(), role), "token_type", "Bearer");
    }
}
