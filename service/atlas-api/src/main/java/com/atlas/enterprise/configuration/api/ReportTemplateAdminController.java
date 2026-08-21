package com.atlas.enterprise.configuration.api;

import com.atlas.enterprise.configuration.application.ConfigurationOverview;
import com.atlas.enterprise.configuration.application.ReportTemplateManagementService;
import com.atlas.enterprise.report.ReportDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/platform/report-templates")
public class ReportTemplateAdminController {
    private static final MediaType DOCX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final ReportTemplateManagementService templates;
    private final ObjectMapper objectMapper;

    public ReportTemplateAdminController(
        ReportTemplateManagementService templates,
        ObjectMapper objectMapper
    ) {
        this.templates = templates;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<ConfigurationOverview> list(
        @RequestParam(defaultValue = "DEV") String environment
    ) {
        return templates.list(environment);
    }

    @PostMapping("/initialize")
    public ReportTemplateManagementService.UploadResult initialize(
        @Valid @RequestBody InitializeRequest request
    ) {
        return templates.initialize(request.environment(), request.operatorId());
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReportTemplateManagementService.UploadResult upload(
        @RequestPart("file") MultipartFile file,
        @RequestParam(defaultValue = "report-template.risk-v1") String configKey,
        @RequestParam(defaultValue = "企业风险监测分析报告") String displayName,
        @RequestParam(required = false) String templateVersion,
        @RequestParam(required = false) String fieldMappingJson,
        @RequestParam(defaultValue = "DEV") String environment,
        @RequestParam String operatorId
    ) throws Exception {
        Map<String, String> mapping = fieldMappingJson == null || fieldMappingJson.isBlank()
            ? Map.of()
            : objectMapper.readValue(fieldMappingJson, new TypeReference<>() {});
        return templates.upload(
            configKey, displayName, file.getOriginalFilename(), file.getBytes(),
            templateVersion, mapping, environment, operatorId
        );
    }

    @GetMapping("/versions/{versionId}/preview")
    public ReportTemplateManagementService.TemplatePreview preview(
        @PathVariable UUID versionId
    ) {
        return templates.preview(versionId);
    }

    @GetMapping("/versions/{versionId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID versionId) {
        ReportDocument document = templates.document(versionId);
        String filename = "atlas-report-template-" + document.templateVersion() + ".docx";
        return ResponseEntity.ok()
            .contentType(DOCX)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .body(document.content());
    }

    public record InitializeRequest(
        @NotBlank String environment,
        @NotBlank String operatorId
    ) {}
}
