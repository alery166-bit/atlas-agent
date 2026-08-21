package com.atlas.enterprise;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuntimeCapabilitiesApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void exposesSanitizedRuntimeConfiguration() throws Exception {
        mockMvc.perform(get("/api/runtime/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service_status", is("UP")))
            .andExpect(jsonPath("$.data_provider.name", is("OFFLINE")))
            .andExpect(jsonPath("$.data_provider.state", is("READY")))
            .andExpect(jsonPath("$.data_provider.details.mode", is("OFFLINE")))
            .andExpect(jsonPath("$.search_providers[*].state", hasItem("TEST_ONLY")))
            .andExpect(jsonPath("$.agent_model.state", is("RULE_FALLBACK")))
            .andExpect(jsonPath(
                "$.risk_scoring.details.rule_version",
                is("RISK_RULES_V1")
            ))
            .andExpect(jsonPath("$.report_generation.details.format", is("DOCX")));
    }
}
