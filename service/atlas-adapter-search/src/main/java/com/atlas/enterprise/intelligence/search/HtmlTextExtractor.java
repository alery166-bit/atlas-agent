package com.atlas.enterprise.intelligence.search;

final class HtmlTextExtractor {
    private HtmlTextExtractor() {}

    static String extract(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
