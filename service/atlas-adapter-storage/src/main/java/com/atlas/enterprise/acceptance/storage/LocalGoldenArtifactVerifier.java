package com.atlas.enterprise.acceptance.storage;

import com.atlas.enterprise.acceptance.port.GoldenArtifactVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalGoldenArtifactVerifier implements GoldenArtifactVerifier {
    private final Path root;
    private final ObjectMapper objectMapper;

    public LocalGoldenArtifactVerifier(
        @Value("${atlas.golden.root:../data/golden}") String root,
        ObjectMapper objectMapper
    ) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public Verification verify(JsonNode manifest) {
        int verified = 0;
        List<String> invalid = new ArrayList<>();
        for (JsonNode sample : manifest.path("cases")) {
            String caseId = sample.path("id").asText();
            boolean valid = true;
            JsonNode artifacts = sample.path("artifacts");
            for (String key : List.of(
                "previous_report", "final_report", "company_json", "operator_decisions"
            )) {
                String relative = artifacts.path(key).asText();
                if (!validArtifact(relative)) {
                    invalid.add(caseId + ":" + key + ":" + relative);
                    valid = false;
                }
            }
            if (valid) verified++;
        }
        return new Verification(verified, invalid);
    }

    private boolean validArtifact(String relative) {
        try {
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)
                || Files.size(path) <= 0 || Files.size(path) > 20L * 1024 * 1024) {
                return false;
            }
            String lower = path.getFileName().toString().toLowerCase();
            if (lower.endsWith(".json")) {
                objectMapper.readTree(path.toFile());
                return true;
            }
            if (lower.endsWith(".docx")) {
                try (InputStream input = Files.newInputStream(path)) {
                    return input.read() == 'P' && input.read() == 'K';
                }
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
