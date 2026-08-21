package com.atlas.enterprise.acceptance.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface GoldenArtifactVerifier {
    Verification verify(JsonNode manifest);

    record Verification(int verifiedCaseCount, List<String> missingOrInvalidArtifacts) {
        public Verification {
            missingOrInvalidArtifacts = missingOrInvalidArtifacts == null
                ? List.of() : List.copyOf(missingOrInvalidArtifacts);
        }
    }
}
