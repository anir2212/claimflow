package dev.claimflow;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"claimflow.extraction.enabled=false",
    "management.prometheus.metrics.export.enabled=true"})
@AutoConfigureMockMvc
class OpenApiIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void publishedContractDocumentsAuthenticationAndClaimRoutes() throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title", is("ClaimFlow local API")))
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme", is("bearer")))
            .andExpect(jsonPath("$.paths['/api/claims'].post.parameters[0].name", is("Idempotency-Key")))
            .andExpect(jsonPath("$.security[0].bearerAuth").exists())
            .andExpect(jsonPath("$.paths['/api/auth/token'].post.security.length()", is(0)));
        mvc.perform(get("/api/claims/mine")).andExpect(status().isForbidden());
    }

    @Test
    void prometheusPublishesBoundedExtractionJobStates() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("claimflow_extraction_jobs")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("status=\"PENDING\"")));
    }
}
