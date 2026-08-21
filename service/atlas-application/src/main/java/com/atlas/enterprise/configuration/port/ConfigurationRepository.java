package com.atlas.enterprise.configuration.port;

import com.atlas.enterprise.configuration.ConfigurationBinding;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationRelease;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.TaskConfigurationSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConfigurationRepository {
    void create(ConfigurationDefinition definition, ConfigurationVersion initialVersion);
    Optional<ConfigurationDefinition> findDefinition(String configKey);
    List<ConfigurationDefinition> findDefinitions();
    Optional<ConfigurationVersion> findVersion(UUID versionId);
    List<ConfigurationVersion> findVersions(UUID configId);
    ConfigurationVersion createDraft(ConfigurationVersion draft);
    ConfigurationVersion updateDraft(UUID versionId, long expectedRowVersion,
                                     String valueJson, String secretRef, String checksum,
                                     String operatorId, Instant at);
    ConfigurationVersion markValidated(UUID versionId, long expectedRowVersion,
                                       String operatorId, String message, Instant at);
    ConfigurationBinding release(ConfigurationRelease release, long expectedBindingRowVersion);
    Optional<ConfigurationBinding> findBinding(UUID configId, String environment);
    List<ConfigurationBinding> findBindings(String environment);
    Optional<ConfigurationRelease> findReleaseByIdempotencyKey(String idempotencyKey);
    TaskConfigurationSnapshot saveTaskSnapshot(TaskConfigurationSnapshot snapshot);
    Optional<TaskConfigurationSnapshot> findTaskSnapshot(UUID taskId, String environment);
}
