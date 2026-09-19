package com.knowledgelink.demo.infrastructure.live;

import java.util.regex.Pattern;

/** Jira wiki 마크업과 GitHub PR 템플릿을 임베딩·화면용 짧은 평문으로 줄인다. */
final class SourceText {
    private static final Pattern JIRA_BLOCK = Pattern.compile("(?s)\\{(code|noformat|quote)(:[^}]*)?}.*?\\{\\1}");
    private static final Pattern JIRA_LINK = Pattern.compile("\\[([^|\\]]+)\\|[^\\]]+]");
    private static final Pattern JIRA_BARE_LINK = Pattern.compile("\\[(https?://[^\\]]+)]");
    private static final Pattern JIRA_HEADING = Pattern.compile("(?m)^h[1-6]\\.\\s*");
    private static final Pattern JIRA_MARKS = Pattern.compile("\\{\\{|}}|\\{}|\\{color(:[^}]*)?}|\\*(?=\\S)|(?<=\\S)\\*");
    private static final Pattern STACK_LINE = Pattern.compile("(?m)^\\s*(at |Caused by:|\\.\\.\\. \\d+ more).*$");
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{1,6}\\s*");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("(?s)```.*?```");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** PR 본문에서 설명이 끝나고 체크리스트·리뷰어 목록이 시작되는 표시. 이후는 버린다. */
    private static final String[] PR_TAIL_MARKERS = {
            "### Committer Checklist", "Committer Checklist", "Reviewers:", "*More detailed description"
    };

    private SourceText() {
    }

    static String jira(String markup, int maxLength) {
        if (markup == null || markup.isBlank()) {
            return "";
        }
        String text = JIRA_BLOCK.matcher(markup).replaceAll(" ");
        text = STACK_LINE.matcher(text).replaceAll("");
        text = JIRA_LINK.matcher(text).replaceAll("$1");
        text = JIRA_BARE_LINK.matcher(text).replaceAll("$1");
        text = JIRA_HEADING.matcher(text).replaceAll("");
        text = JIRA_MARKS.matcher(text).replaceAll("");
        return truncate(text, maxLength);
    }

    static String pullRequest(String body, int maxLength) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text = body;
        for (String marker : PR_TAIL_MARKERS) {
            int index = text.indexOf(marker);
            if (index >= 0) {
                text = text.substring(0, index);
            }
        }
        text = HTML_COMMENT.matcher(text).replaceAll(" ");
        text = MARKDOWN_FENCE.matcher(text).replaceAll(" ");
        text = MARKDOWN_HEADING.matcher(text).replaceAll("");
        return truncate(text, maxLength);
    }

    static String truncate(String text, int maxLength) {
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").strip();
        if (collapsed.length() <= maxLength) {
            return collapsed;
        }
        int cut = collapsed.lastIndexOf(' ', maxLength);
        return collapsed.substring(0, cut > maxLength / 2 ? cut : maxLength).strip() + "…";
    }
}
