package com.atlas.enterprise.report.docx;

import com.atlas.enterprise.report.ReportDocument;
import com.atlas.enterprise.report.application.ReportValidationException;
import com.atlas.enterprise.report.port.ManagedReportTemplateStore;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalManagedReportTemplateStore implements ManagedReportTemplateStore {
    private static final int MAX_DOCX_BYTES = 20 * 1024 * 1024;
    private static final List<String> MARKERS = List.of(
        "企业名称", "统一社会信用代码", "法定代表人",
        "登记状态", "风险评分", "风险等级", "网络舆情"
    );

    private final Path root;

    public LocalManagedReportTemplateStore(
        @Value("${atlas.report.managed-template-root:./.data/report-templates}") String root
    ) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredTemplate save(String originalFilename, byte[] content, String operatorId) {
        validateFilename(originalFilename);
        if (content == null || content.length == 0 || content.length > MAX_DOCX_BYTES) {
            throw new ReportValidationException("DOCX template size must be between 1 byte and 20 MB");
        }
        Inspection inspection = inspect(content);
        if (!inspection.valid()) {
            throw new ReportValidationException("Invalid DOCX template: " + inspection.message());
        }
        String hash = OoxmlDocxSupport.sha256(content);
        Path target = resolve(hash);
        try {
            Files.createDirectories(root);
            try {
                Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                if (!hash.equals(OoxmlDocxSupport.sha256(Files.readAllBytes(target)))) {
                    throw new ReportValidationException("Managed template hash collision detected");
                }
            }
        } catch (IOException exception) {
            throw new ReportValidationException("Could not store managed DOCX template", exception);
        }
        return new StoredTemplate(hash, originalFilename.trim(), hash, content.length, inspection);
    }

    @Override
    public ReportDocument load(
        String artifactId,
        String templateVersion,
        String expectedHash
    ) {
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new ReportValidationException("Managed template artifact id is invalid");
        }
        Path path = resolve(artifactId);
        try {
            if (!Files.isRegularFile(path)) {
                throw new ReportValidationException("Managed DOCX template does not exist");
            }
            byte[] content = Files.readAllBytes(path);
            String actual = OoxmlDocxSupport.sha256(content);
            if (!actual.equals(artifactId) || !actual.equals(expectedHash)) {
                throw new ReportValidationException("Managed DOCX template hash does not match configuration");
            }
            return new ReportDocument("managed:" + artifactId, templateVersion, actual, content);
        } catch (IOException exception) {
            throw new ReportValidationException("Could not load managed DOCX template", exception);
        }
    }

    @Override
    public Inspection inspect(byte[] content) {
        try {
            if (content == null || content.length < 4
                || content[0] != 'P' || content[1] != 'K') {
                return invalid("File is not an OOXML ZIP package");
            }
            Map<String, byte[]> parts = OoxmlDocxSupport.unzip(content);
            if (!parts.containsKey("[Content_Types].xml")
                || !parts.containsKey("word/document.xml")) {
                return invalid("Required DOCX package parts are missing");
            }
            var document = OoxmlDocxSupport.parse(parts.get("word/document.xml"));
            int paragraphs = OoxmlDocxSupport.bodyParagraphs(document).size();
            int tables = OoxmlDocxSupport.bodyTables(document).size();
            String text = OoxmlDocxSupport.text(document.getDocumentElement());
            List<String> detected = MARKERS.stream().filter(text::contains).toList();
            List<String> missing = MARKERS.stream().filter(marker -> !text.contains(marker)).toList();
            Map<String, String> mapping = new LinkedHashMap<>();
            mapping.put("company_name", "企业名称");
            mapping.put("unified_credit_code", "统一社会信用代码");
            mapping.put("legal_representative", "法定代表人");
            mapping.put("registration_status", text.contains("经营状态") ? "经营状态" : "企业状态");
            mapping.put("risk_score", "风险分：");
            mapping.put("risk_level", "风险标签：");
            mapping.put("public_evidence", "网络舆情");
            boolean structurallyValid = paragraphs >= 5 && tables >= 2
                && text.contains("统一社会信用代码") && text.contains("法定代表人");
            return new Inspection(
                structurallyValid, paragraphs, tables, detected, missing,
                Map.copyOf(mapping), structurallyValid
                    ? "DOCX package and core report structure passed"
                    : "Core enterprise report markers or table structure are missing"
            );
        } catch (RuntimeException exception) {
            return invalid(exception.getMessage() == null
                ? "DOCX package could not be inspected" : exception.getMessage());
        }
    }

    @Override
    public boolean containsText(byte[] content, String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Map<String, byte[]> parts = OoxmlDocxSupport.unzip(content);
            byte[] documentXml = parts.get("word/document.xml");
            if (documentXml == null) return false;
            var document = OoxmlDocxSupport.parse(documentXml);
            return OoxmlDocxSupport.text(document.getDocumentElement()).contains(value.trim());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Path resolve(String artifactId) {
        Path value = root.resolve(artifactId + ".docx").normalize();
        if (!value.startsWith(root)) {
            throw new ReportValidationException("Managed template path is invalid");
        }
        return value;
    }

    private static Inspection invalid(String message) {
        return new Inspection(false, 0, 0, List.of(), new ArrayList<>(MARKERS), Map.of(), message);
    }

    private static void validateFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()
            || !originalFilename.toLowerCase().endsWith(".docx")
            || originalFilename.length() > 255) {
            throw new ReportValidationException("Template filename must be a valid .docx name");
        }
    }
}
