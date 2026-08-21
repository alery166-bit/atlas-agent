package com.atlas.enterprise.report.docx;

import com.atlas.enterprise.report.ReportDocument;
import com.atlas.enterprise.report.application.ReportValidationException;
import com.atlas.enterprise.report.port.ReportDocumentSource;
import com.atlas.enterprise.report.port.ManagedReportTemplateStore;
import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.application.ReportTemplateConfigurationCodec;
import com.atlas.enterprise.configuration.application.TaskConfigurationResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalReportDocumentSource implements ReportDocumentSource {
    private final Path templatePath;
    private final Path previousRoot;
    private final String templateAlias;
    private final TaskConfigurationResolver configurations;
    private final ReportTemplateConfigurationCodec templateCodec;
    private final ManagedReportTemplateStore managedTemplates;

    public LocalReportDocumentSource(
        @Value("${atlas.report.template-path}") String templatePath,
        @Value("${atlas.report.previous-root}") String previousRoot,
        @Value("${atlas.report.template-alias:report-v1}") String templateAlias,
        TaskConfigurationResolver configurations,
        ReportTemplateConfigurationCodec templateCodec,
        ManagedReportTemplateStore managedTemplates
    ) {
        this.templatePath = absolute(templatePath);
        this.previousRoot = absolute(previousRoot);
        this.templateAlias = templateAlias;
        this.configurations = configurations;
        this.templateCodec = templateCodec;
        this.managedTemplates = managedTemplates;
    }

    @Override
    public ReportDocument loadTemplate() {
        byte[] content = read(templatePath);
        String hash = OoxmlDocxSupport.sha256(content);
        return new ReportDocument(
            templatePath.toString(),
            "V1-" + hash.substring(0, 12),
            hash,
            content
        );
    }

    @Override
    public ReportDocument loadTemplate(UUID taskId) {
        return configurations.resolve(taskId, ConfigurationCategory.REPORT_TEMPLATE).stream()
            .filter(item -> templateCodec.isTemplateDocument(item.version().valueJson()))
            .map(item -> templateCodec.parse(item.version().valueJson()))
            .filter(ReportTemplateConfigurationCodec.TemplateDefinition::enabled)
            .findFirst()
            .map(definition -> {
                ReportDocument document = managedTemplates.load(
                    definition.artifactId(), definition.templateVersion(), definition.contentHash()
                );
                return new ReportDocument(
                    document.reference(), document.templateVersion(), document.contentHash(),
                    document.content(), definition.fieldMapping()
                );
            })
            .orElseGet(this::loadTemplate);
    }

    @Override
    public ReportDocument loadPrevious(String reference) {
        if (templateAlias.equals(reference)) {
            ReportDocument template = loadTemplate();
            return new ReportDocument(
                reference,
                template.templateVersion(),
                template.contentHash(),
                template.content()
            );
        }
        Path candidate = Path.of(reference);
        Path resolved = candidate.isAbsolute()
            ? candidate.toAbsolutePath().normalize()
            : previousRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(previousRoot)) {
            throw new ReportValidationException(
                "Previous report must stay inside the configured report root"
            );
        }
        byte[] content = read(resolved);
        return new ReportDocument(
            reference,
            "PREVIOUS",
            OoxmlDocxSupport.sha256(content),
            content
        );
    }

    private static byte[] read(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new ReportValidationException("DOCX file not found: " + path);
            }
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new ReportValidationException("Could not read DOCX file: " + path, exception);
        }
    }

    private static Path absolute(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }
}
