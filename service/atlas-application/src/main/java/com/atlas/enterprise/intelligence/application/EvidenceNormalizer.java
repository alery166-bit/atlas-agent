package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.intelligence.EntityMatchStatus;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.CompanyAlias;
import com.atlas.enterprise.risk.RiskType;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class EvidenceNormalizer {
    private static final Set<String> TRACKING_KEYS = Set.of(
        "spm", "from", "source", "ref", "referrer", "track", "tracking"
    );

    public String normalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI input = new URI(value.trim());
            String scheme = lower(input.getScheme());
            String host = lower(input.getHost());
            if (scheme == null || host == null) {
                return null;
            }
            int port = input.getPort();
            if (("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String path = input.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            String query = normalizeQuery(input.getRawQuery());
            return new URI(scheme, input.getUserInfo(), host, port, path, query, null)
                .normalize()
                .toASCIIString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    public String sourceDomain(String normalizedUrl) {
        if (normalizedUrl == null) {
            return null;
        }
        try {
            return URI.create(normalizedUrl).getHost();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public String contentHash(String title, String snippet) {
        return sha256(normalizeText(title) + "\n" + normalizeText(snippet));
    }

    public String dedupeKey(String normalizedUrl, String contentHash) {
        return normalizedUrl == null || normalizedUrl.isBlank()
            ? "content:" + contentHash
            : "url:" + normalizedUrl;
    }

    public EntityMatch matchEntity(
        CompanyFacts company,
        String title,
        String snippet
    ) {
        return matchEntity(company, List.of(), title, snippet);
    }

    public EntityMatch matchEntity(
        CompanyFacts company,
        List<CompanyAlias> aliases,
        String title,
        String snippet
    ) {
        String content = normalizeText(title) + normalizeText(snippet);
        for (CompanyIdentityTerms.IdentityTerm term
            : new CompanyIdentityTerms().confirmed(company, aliases)) {
            if (contains(content, normalizeText(term.value()))) {
                return new EntityMatch(
                    EntityMatchStatus.MATCHED,
                    term.value(),
                    term.type()
                );
            }
        }
        return new EntityMatch(EntityMatchStatus.POSSIBLE_MATCH, null, null);
    }

    public RiskType classifyRisk(RiskType target, String title, String snippet) {
        String content = normalizeText(title) + normalizeText(snippet);
        if (matchesRisk(target, content)) {
            return target;
        }
        for (RiskType candidate : List.of(
            RiskType.OUT_OF_CONTACT,
            RiskType.WAGE_ARREARS,
            RiskType.STORE_CLOSURE
        )) {
            if (matchesRisk(candidate, content)) {
                return candidate;
            }
        }
        return RiskType.OTHER;
    }

    public boolean hasAccessibleCitation(String normalizedUrl) {
        return normalizedUrl != null
            && (normalizedUrl.startsWith("https://") || normalizedUrl.startsWith("http://"));
    }

    public boolean isBackgroundProfileWithoutRisk(
        String normalizedUrl,
        String title,
        String snippet
    ) {
        String content = normalizeText(title) + normalizeText(snippet);
        if (hasExplicitRiskSignal(content)) {
            return false;
        }
        String domain = sourceDomain(normalizedUrl);
        String path = normalizedUrl == null ? "" : normalizedUrl.toLowerCase(Locale.ROOT);
        boolean directoryDomain = domain != null && (
            domain.equals("baike.baidu.com")
                || domain.endsWith(".qcc.com") || domain.equals("qcc.com")
                || domain.endsWith(".tianyancha.com") || domain.equals("tianyancha.com")
                || domain.endsWith(".qizhidao.com") || domain.equals("qizhidao.com")
                || domain.endsWith(".pitchhub.com") || domain.equals("pitchhub.com")
                || domain.equals("www.linkedin.com") || domain.equals("linkedin.com")
        );
        boolean profilePath = path.contains("/baike/")
            || path.contains("/firm/")
            || path.contains("/company/")
            || path.contains("/enterprise/");
        boolean profileLanguage = containsAny(
            content,
            "百度百科", "企业百科", "公司简介", "企业简介", "企业信息查询",
            "工商信息查询", "企业基本信息", "公司基本信息", "公司主页"
        );
        return directoryDomain || profilePath || profileLanguage;
    }

    private static boolean hasExplicitRiskSignal(String content) {
        return containsAny(
            content,
            "失联", "联系不上", "无法联系", "无人接听",
            "拖欠工资", "欠薪", "工资未发", "讨薪",
            "闭店", "关店", "门店关闭", "停止营业", "停业",
            "行政处罚", "环保处罚", "严重违法", "经营异常",
            "被执行", "失信", "限制高消费", "破产", "清算",
            "投诉", "退款", "维权", "纠纷", "裁判文书", "诉讼"
        );
    }

    private static boolean matchesRisk(RiskType type, String content) {
        return switch (type) {
            case OUT_OF_CONTACT -> containsAny(
                content,
                "失联", "联系不上", "无法联系", "无人接听"
            );
            case WAGE_ARREARS -> containsAny(
                content,
                "拖欠工资", "欠薪", "工资未发", "讨薪"
            );
            case STORE_CLOSURE -> containsAny(
                content,
                "闭店", "关店", "门店关闭", "停止营业", "停业"
            );
            default -> false;
        };
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        List<String> retained = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            String key = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (!lowerKey.startsWith("utm_") && !TRACKING_KEYS.contains(lowerKey)) {
                retained.add(part);
            }
        }
        retained.sort(Comparator.naturalOrder());
        return retained.isEmpty() ? null : String.join("&", retained);
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    private static boolean contains(String content, String value) {
        return value != null && !value.isBlank() && content.contains(value);
    }

    private static boolean containsAny(String content, String... terms) {
        for (String term : terms) {
            if (content.contains(normalizeText(term))) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append("%02x".formatted(item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record EntityMatch(
        EntityMatchStatus status,
        String matchedTerm,
        String matchedTermType
    ) {}
}
