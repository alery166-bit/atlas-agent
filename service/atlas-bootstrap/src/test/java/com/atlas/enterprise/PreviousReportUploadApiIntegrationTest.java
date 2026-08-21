package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreviousReportUploadApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void exposesTheUploadedReportScoreSeparatelyInWorkspace() throws Exception {
        byte[] template = Files.readAllBytes(Path.of(
            "../../data/templates/北京简熹和食品有限公司_企业风险监测分析报告20260714.docx"
        ));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "北京简熹和食品有限公司_旧报告.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            template
        );
        String uploadBody = mockMvc.perform(multipart("/api/files/previous-reports").file(file))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String fileId = objectMapper.readTree(uploadBody).path("file_id").asText();

        String taskBody = mockMvc.perform(post("/api/tasks")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "previous-report-score-workspace")
                .header("X-Operator-Id", "operator-1")
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "prompt", "更新北京简熹和食品有限公司的风险报告",
                    "company_query", "北京简熹和食品有限公司",
                    "previous_report_file_id", fileId
                ))))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        JsonNode task = objectMapper.readTree(taskBody);

        mockMvc.perform(get("/api/tasks/{taskId}/workspace", task.path("task_id").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previous_report_score").doesNotExist());
    }

    @Test
    void storesDocxWithGeneratedSafeReference() throws Exception {
        byte[] template = Files.readAllBytes(Path.of(
            "../../data/templates/北京简熹和食品有限公司_企业风险监测分析报告20260714.docx"
        ));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "../../不可信路径/旧报告.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            template
        );

        mockMvc.perform(multipart("/api/files/previous-reports").file(file))
            .andExpect(status().isCreated())
            .andExpect(jsonPath(
                "$.file_id",
                matchesPattern(
                    "uploads/[0-9a-f-]{36}\\.docx"
                )
            ))
            .andExpect(jsonPath(
                "$.original_filename",
                is("旧报告.docx")
            ))
            .andExpect(jsonPath("$.size", is(template.length)))
            .andExpect(jsonPath(
                "$.content_sha256",
                matchesPattern("[0-9a-f]{64}")
            ))
            .andExpect(jsonPath(
                "$.parsed_report.report_type",
                is("STANDARD_RISK_REPORT")
            ))
            .andExpect(jsonPath("$.parsed_report.supported_for_update", is(true)))
            .andExpect(jsonPath(
                "$.parsed_report.source_content_sha256",
                matchesPattern("[0-9a-f]{64}")
            ));
    }

    @Test
    void rejectsNonDocxPayload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "风险报告.txt",
            "text/plain",
            "not a docx".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/files/previous-reports").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath(
                "$.code",
                is("INVALID_PREVIOUS_REPORT_UPLOAD")
            ));
    }

    @Test
    void rejectsZipHeaderWithoutRequiredDocxParts() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "伪造报告.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}
        );

        mockMvc.perform(multipart("/api/files/previous-reports").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath(
                "$.code",
                is("INVALID_PREVIOUS_REPORT_UPLOAD")
            ));
    }
}
