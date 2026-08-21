package com.atlas.enterprise.configuration.application;

public class SkillDisabledException extends IllegalStateException {
    public SkillDisabledException(String skillKey) {
        super("Skill is disabled for this task configuration: " + skillKey);
    }
}
