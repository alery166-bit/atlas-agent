package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.report.port.ManagedReportTemplateStore;
import org.springframework.stereotype.Component;

@Component
public class ReportTemplateConfigurationValidator implements ConfigurationContentValidator {
    private final ReportTemplateConfigurationCodec codec;
    private final ManagedReportTemplateStore templates;

    public ReportTemplateConfigurationValidator(
        ReportTemplateConfigurationCodec codec,
        ManagedReportTemplateStore templates
    ) {
        this.codec = codec;
        this.templates = templates;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.REPORT_TEMPLATE;
    }

    @Override
    public String validate(String valueJson) {
        var definition = codec.parse(valueJson);
        var document = templates.load(
            definition.artifactId(), definition.templateVersion(), definition.contentHash()
        );
        var inspection = templates.inspect(document.content());
        if (!inspection.valid()) {
            throw new IllegalArgumentException(
                "DOCX template structure is invalid: " + inspection.message()
            );
        }
        for (String field : ReportTemplateConfigurationCodec.RUNTIME_MAPPING_FIELDS) {
            String locator = definition.fieldMapping().get(field);
            if (!templates.containsText(document.content(), locator)) {
                throw new IllegalArgumentException(
                    "DOCX template does not contain the configured locator for "
                        + field + ": " + locator
                );
            }
        }
        return "DOCX structure passed: " + inspection.paragraphCount() + " paragraphs, "
            + inspection.tableCount() + " tables; required field mapping complete";
    }
}
