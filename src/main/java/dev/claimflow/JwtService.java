package dev.claimflow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper json;
    private final byte[] secret;
    private final long tokenSeconds;

    public record Identity(String subject, String role) {}

    public JwtService(ObjectMapper json, @Value("${claimflow.jwt-secret}") String secret,
                      @Value("${claimflow.token-seconds}") long tokenSeconds) {
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tokenSeconds = tokenSeconds;
    }

    public String issue(String subject, String role) {
        try {
            String header = ENCODER.encodeToString(json.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = ENCODER.encodeToString(json.writeValueAsBytes(Map.of(
                "sub", subject, "role", role, "exp", Instant.now().getEpochSecond() + tokenSeconds)));
            String unsigned = header + "." + payload;
            return unsigned + "." + ENCODER.encodeToString(sign(unsigned));
        } catch (Exception error) {
            throw new IllegalStateException("Could not issue token", error);
        }
    }

    public Identity verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Malformed token");
            String unsigned = parts[0] + "." + parts[1];
            if (!java.security.MessageDigest.isEqual(sign(unsigned), DECODER.decode(parts[2]))) {
                throw new IllegalArgumentException("Invalid token signature");
            }
            var header = json.readTree(DECODER.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) throw new IllegalArgumentException("Unexpected algorithm");
            var payload = json.readTree(DECODER.decode(parts[1]));
            if (payload.path("exp").asLong(0) <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("Expired token");
            }
            String role = payload.path("role").asText();
            String subject = payload.path("sub").asText();
            if (subject.isBlank() || !java.util.Set.of("EMPLOYEE", "MANAGER", "FINANCE").contains(role)) {
                throw new IllegalArgumentException("Invalid token identity");
            }
            return new Identity(subject, role);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid token", error);
        }
    }

    private byte[] sign(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }
}
