package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationVersionStatus;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prevents new tasks from silently falling back to unversioned built-in skills.
 * Existing tasks remain compatible because their frozen manifests are not changed.
 */
@Component
public class PublishedSkillSetGuard {
    private static final Map<String, String> REQUIRED_SKILLS = Map.of(
        "skill.company.resolve", "company.resolve",
        "skill.company.snapshot", "company.snapshot",
        "skill.intelligence.search", "intelligence.search",
        "skill.risk.score", "risk.score",
        "skill.report.generate", "report.generate"
    );

    private final ConfigurationRepository configurations;
    private final SkillConfigurationCodec codec;
    private final boolean requiredForNewTasks;

    public PublishedSkillSetGuard(
        ConfigurationRepository configurations,
        SkillConfigurationCodec codec,
        @Value("${atlas.skills.require-published-for-new-tasks:true}")
        boolean requiredForNewTasks
    ) {
        this.configurations = configurations;
        this.codec = codec;
        this.requiredForNewTasks = requiredForNewTasks;
    }

    public void requireReady(String environment) {
        if (!requiredForNewTasks) return;
        String normalizedEnvironment = environment == null || environment.isBlank()
            ? "DEV" : environment.trim().toUpperCase();
        Map<String, ConfigurationDefinition> definitions = new LinkedHashMap<>();
        configurations.findDefinitions().stream()
            .filter(item -> item.category() == ConfigurationCategory.SKILL)
            .forEach(item -> definitions.put(item.configKey(), item));

        List<String> problems = new ArrayList<>();
        REQUIRED_SKILLS.forEach((configKey, skillKey) -> {
            ConfigurationDefinition definition = definitions.get(configKey);
            if (definition == null) {
                problems.add(configKey + " 未登记");
                return;
            }
            var binding = configurations.findBinding(definition.configId(), normalizedEnvironment)
                .orElse(null);
            if (binding == null) {
                problems.add(configKey + " 未发布");
                return;
            }
            var version = configurations.findVersion(binding.activeVersionId()).orElse(null);
            if (version == null || version.status() != ConfigurationVersionStatus.PUBLISHED) {
                problems.add(configKey + " 生效版本无效");
                return;
            }
            try {
                var document = codec.parse(version.valueJson());
                if (!skillKey.equals(document.skillKey())) {
                    problems.add(configKey + " 执行契约不匹配");
                } else if (!document.enabled()) {
                    problems.add(configKey + " 已停用");
                }
            } catch (IllegalArgumentException exception) {
                problems.add(configKey + " 配置无效");
            }
        });
        if (!problems.isEmpty()) {
            throw new ConfigurationConflictException(
                "新任务要求 5 个内置 Skill 均已发布且启用：" + String.join("；", problems)
            );
        }
    }
}
