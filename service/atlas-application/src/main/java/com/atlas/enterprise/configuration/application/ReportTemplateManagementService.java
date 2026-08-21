package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import com.atlas.enterprise.report.ReportDocument;
import com.atlas.enterprise.report.port.ManagedReportTemplateStore;
import com.atlas.enterprise.report.port.ReportDocumentSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportTemplateManagementService {
    public static final String DEFAULT_KEY = "report-template.risk-v1";

    private final ConfigurationApplicationService configurations;
    private final ConfigurationRepository repository;
    private final ReportTemplateConfigurationCodec codec;
    private final ManagedReportTemplateStore templates;
    private final ReportDocumentSource documents;

    public ReportTemplateManagementService(
        ConfigurationApplicationService configurations,
        ConfigurationRepository repository,
        ReportTemplateConfigurationCodec codec,
        ManagedReportTemplateStore templates,
        ReportDocumentSource documents
    ) {
        this.configurations = configurations;
        this.repository = repository;
        this.codec = codec;
        this.templates = templates;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public List<ConfigurationOverview> list(String environment) {
        return configurations.list(environment).stream()
            .filter(item -> item.definition().category() == ConfigurationCategory.REPORT_TEMPLATE)
            .filter(item -> item.versions().stream()
                .anyMatch(version -> codec.isTemplateDocument(version.valueJson())))
            .toList();
    }

    @Transactional
    public UploadResult initialize(String environment, String operatorId) {
        var existing = repository.findDefinition(DEFAULT_KEY);
        if (existing.isPresent()) {
            return new UploadResult(requireOverview(DEFAULT_KEY, environment), null, false);
        }
        ReportDocument source = documents.loadTemplate();
        var artifact = templates.save(
            "企业风险监测分析报告V1.docx", source.content(), operatorId
        );
        String valueJson = codec.document(
            artifact, source.templateVersion(), codec.defaultMapping(), defaultDependencies()
        );
        ConfigurationOverview created = configurations.create(
            DEFAULT_KEY, ConfigurationCategory.REPORT_TEMPLATE,
            "企业风险监测分析报告", "V1 正式 DOCX 模板",
            false, valueJson, null, operatorId
        );
        return new UploadResult(created, artifact, true);
    }

    @Transactional
    public UploadResult upload(
        String configKey,
        String displayName,
        String originalFilename,
        byte[] content,
        String templateVersion,
        Map<String, String> fieldMapping,
        String environment,
        String operatorId
    ) {
        String key = required(configKey, "configKey");
        var artifact = templates.save(originalFilename, content, operatorId);
        String version = templateVersion == null || templateVersion.isBlank()
            ? "V1-" + artifact.contentHash().substring(0, 12)
            : templateVersion.trim();
        Map<String, String> mapping = fieldMapping == null || fieldMapping.isEmpty()
            ? codec.defaultMapping()
            : Map.copyOf(fieldMapping);
        String valueJson = codec.document(artifact, version, mapping, defaultDependencies());
        if (repository.findDefinition(key).isEmpty()) {
            configurations.create(
                key, ConfigurationCategory.REPORT_TEMPLATE,
                required(displayName, "displayName"), "Managed DOCX report template",
                false, valueJson, null, operatorId
            );
        } else {
            configurations.createDraft(key, valueJson, null, operatorId);
        }
        return new UploadResult(requireOverview(key, environment), artifact, true);
    }

    @Transactional(readOnly = true)
    public TemplatePreview preview(UUID versionId) {
        ConfigurationVersion version = repository.findVersion(versionId)
            .orElseThrow(() -> new ConfigurationNotFoundException(
                "Configuration version not found: " + versionId
            ));
        var definition = codec.parse(version.valueJson());
        ReportDocument document = templates.load(
            definition.artifactId(), definition.templateVersion(), definition.contentHash()
        );
        return new TemplatePreview(
            definition.templateVersion(), definition.originalFilename(),
            definition.contentHash(), definition.fieldMapping(),
            templates.inspect(document.content())
        );
    }

    @Transactional(readOnly = true)
    public ReportDocument document(UUID versionId) {
        ConfigurationVersion version = repository.findVersion(versionId)
            .orElseThrow(() -> new ConfigurationNotFoundException(
                "Configuration version not found: " + versionId
            ));
        var definition = codec.parse(version.valueJson());
        return templates.load(
            definition.artifactId(), definition.templateVersion(), definition.contentHash()
        );
    }

    private ConfigurationOverview requireOverview(String key, String environment) {
        return list(environment).stream()
            .filter(item -> item.definition().configKey().equals(key))
            .findFirst().orElseThrow(() -> new ConfigurationNotFoundException(
                "Report template configuration not found: " + key
            ));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static List<SkillConfigurationCodec.Dependency> defaultDependencies() {
        return List.of(
            new SkillConfigurationCodec.Dependency(
                "skill.report.generate", ConfigurationCategory.SKILL, false
            ),
            new SkillConfigurationCodec.Dependency(
                "risk.rules.standard-v1", ConfigurationCategory.RULES, false
            ),
            new SkillConfigurationCodec.Dependency(
                "model.cited-llm.primary", ConfigurationCategory.MODEL, false
            )
        );
    }

    public record UploadResult(
        ConfigurationOverview configuration,
        ManagedReportTemplateStore.StoredTemplate artifact,
        boolean changed
    ) {}

    public record TemplatePreview(
        String templateVersion,
        String originalFilename,
        String contentHash,
        Map<String, String> fieldMapping,
        ManagedReportTemplateStore.Inspection inspection
    ) {}
}
