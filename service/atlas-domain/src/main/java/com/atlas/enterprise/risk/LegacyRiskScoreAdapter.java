package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.util.List;

/**
 * Side-effect-free compatibility boundary for the materialized legacy score.
 *
 * <p>The old service cannot be called directly because it reads Elasticsearch,
 * performs region-specific lookups and writes current/history indices. During
 * incremental migration, its already materialized score is retained as the
 * deterministic baseline and is never confused with an operator score.</p>
 */
public final class LegacyRiskScoreAdapter {
    public LegacyScoreBaseline adapt(BigDecimal legacyScore) {
        if (legacyScore == null) {
            return new LegacyScoreBaseline(BigDecimal.ZERO, List.of());
        }
        return new LegacyScoreBaseline(
            legacyScore,
            List.of(new RiskRuleHit(
                "LEGACY_MATERIALIZED_SCORE",
                "旧评分资产基线",
                null,
                legacyScore,
                "RULE_SCORE",
                List.of("company_facts.legacy_score")
            ))
        );
    }

    public record LegacyScoreBaseline(BigDecimal score, List<RiskRuleHit> ruleHits) {
        public LegacyScoreBaseline {
            ruleHits = List.copyOf(ruleHits);
        }
    }
}
