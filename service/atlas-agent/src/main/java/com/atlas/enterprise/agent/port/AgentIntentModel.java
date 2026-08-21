package com.atlas.enterprise.agent.port;

import java.util.Optional;

/**
 * Optional model-backed intent extraction port.
 *
 * <p>The core agent remains usable when no implementation is configured.
 * Implementations should return an empty result when the model cannot produce
 * a schema-conformant prediction.</p>
 */
public interface AgentIntentModel {
    Optional<AgentIntentPrediction> predict(AgentIntentModelRequest request);

    default boolean available() {
        return true;
    }
}
