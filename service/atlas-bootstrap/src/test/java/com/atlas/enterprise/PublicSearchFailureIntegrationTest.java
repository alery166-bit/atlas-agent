package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.SearchBatch;
import com.atlas.enterprise.intelligence.SearchRequest;
import com.atlas.enterprise.intelligence.port.PublicSearchProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PublicSearchFailureIntegrationTest.FailureProviderConfiguration.class)
class PublicSearchFailureIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void requiredSearchFailureStopsWorkflow() throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "w5-required-search-failure")
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "更新企业风险报告",
                    "company_query", "91110101JSON000001",
                    "previous_report_file_id", "report-v1"
                ))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String taskId = objectMapper.readTree(response).path("task_id").asText();

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("SOURCE_FAILED")))
            .andExpect(jsonPath("$.failed_step", is("SEARCH_PUBLIC_INTELLIGENCE")))
            .andExpect(jsonPath("$.error_code", is("SEARCH_PROVIDER_UNAVAILABLE")));

        mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/searches",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$[?(@.provider == 'required-failure-fixture')]",
                hasSize(2)
            ))
            .andExpect(jsonPath(
                "$[?(@.failure_code == 'UPSTREAM_TIMEOUT')]",
                hasSize(2)
            ));
    }

    @TestConfiguration
    static class FailureProviderConfiguration {
        @Bean
        PublicSearchProvider requiredFailureProvider() {
            return new PublicSearchProvider() {
                @Override
                public SearchBatch search(SearchRequest request) {
                    return SearchBatch.failed(
                        "required-failure-fixture",
                        "UPSTREAM_TIMEOUT",
                        "Required search fixture timed out",
                        request.requestedAt()
                    );
                }

                @Override
                public ProviderCapabilities capabilities() {
                    return new ProviderCapabilities(
                        "required-failure-fixture",
                        ProviderCapabilities.ProviderMode.FIXTURE,
                        true,
                        true
                    );
                }
            };
        }
    }
}
