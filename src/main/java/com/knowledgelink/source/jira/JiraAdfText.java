package com.knowledgelink.source.jira;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Jira 문서 형식(ADF)을 평문으로 옮긴다. 코드 블록과 링크 주소는 유지하고 첨부·이미지는 뺀다(05 NORMALIZE).
 * 모르는 노드는 버리지 않고 안쪽 내용을 읽는다. 공급자가 노드를 추가해도 본문이 사라지지 않게 하기 위해서다.
 */
final class JiraAdfText {

    private JiraAdfText() {
    }

    /** 비어 있으면 null. 이전 API처럼 문자열이 오면 그대로 쓴다. */
    static String toText(JsonNode document) {
        if (document == null || document.isMissingNode() || document.isNull()) {
            return null;
        }
        if (document.isString()) {
            return blankToNull(document.asString());
        }
        return blankToNull(block(document).replaceAll("\n{3,}", "\n\n").strip());
    }

    private static String block(JsonNode node) {
        String type = node.path("type").asString("");
        return switch (type) {
            case "paragraph", "heading" -> inline(node.path("content"));
            case "codeBlock" -> "```" + node.path("attrs").path("language").asString("") + "\n"
                    + inline(node.path("content")) + "\n```";
            case "blockquote" -> prefixLines(blocks(node.path("content"), "\n\n"), "> ");
            case "bulletList" -> list(node, false);
            case "orderedList" -> list(node, true);
            case "taskList", "decisionList" -> list(node, false);
            case "rule" -> "---";
            case "table" -> table(node);
            case "media", "mediaSingle", "mediaGroup", "mediaInline" -> "";
            case "expand", "nestedExpand" -> joinNonBlank("\n\n",
                    List.of(node.path("attrs").path("title").asString(""), blocks(node.path("content"), "\n\n")));
            default -> hasBlockChildren(node) ? blocks(node.path("content"), "\n\n") : inline(node.path("content"));
        };
    }

    private static String blocks(JsonNode content, String separator) {
        List<String> parts = new ArrayList<>();
        for (JsonNode child : content) {
            parts.add(block(child));
        }
        return joinNonBlank(separator, parts);
    }

    private static String list(JsonNode list, boolean ordered) {
        int number = list.path("attrs").path("order").asInt(1);
        List<String> items = new ArrayList<>();
        for (JsonNode item : list.path("content")) {
            String marker = switch (item.path("type").asString("")) {
                case "taskItem" -> "DONE".equals(item.path("attrs").path("state").asString("")) ? "- [x] " : "- [ ] ";
                default -> ordered ? (number++) + ". " : "- ";
            };
            String body = hasBlockChildren(item) ? blocks(item.path("content"), "\n") : inline(item.path("content"));
            items.add(marker + body.replace("\n", "\n  "));
        }
        return String.join("\n", items);
    }

    private static String table(JsonNode table) {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : table.path("content")) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row.path("content")) {
                cells.add(blocks(cell.path("content"), " ").replace("\n", " "));
            }
            rows.add(String.join(" | ", cells));
        }
        return String.join("\n", rows);
    }

    private static String inline(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode node : content) {
            JsonNode attrs = node.path("attrs");
            switch (node.path("type").asString("")) {
                case "text" -> text.append(markedText(node));
                case "hardBreak" -> text.append('\n');
                case "mention", "status" -> text.append(attrs.path("text").asString(""));
                case "emoji" -> text.append(attrs.path("text").asString(attrs.path("shortName").asString("")));
                case "inlineCard", "blockCard", "embedCard" -> text.append(attrs.path("url").asString(""));
                case "date" -> text.append(date(attrs.path("timestamp").asString("")));
                default -> text.append(inline(node.path("content")));
            }
        }
        return text.toString();
    }

    private static String markedText(JsonNode node) {
        String text = node.path("text").asString("");
        for (JsonNode mark : node.path("marks")) {
            switch (mark.path("type").asString("")) {
                case "code" -> text = "`" + text + "`";
                case "link" -> {
                    String href = mark.path("attrs").path("href").asString("");
                    if (!href.isBlank() && !href.equals(text)) {
                        text = text + " (" + href + ")";
                    }
                }
                default -> {
                }
            }
        }
        return text;
    }

    private static String date(String epochMillis) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(epochMillis)).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (NumberFormatException e) {
            return epochMillis;
        }
    }

    private static boolean hasBlockChildren(JsonNode node) {
        for (JsonNode child : node.path("content")) {
            if (child.has("content") || "rule".equals(child.path("type").asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static String prefixLines(String text, String prefix) {
        return text.isEmpty() ? text : prefix + text.replace("\n", "\n" + prefix);
    }

    private static String joinNonBlank(String separator, List<String> parts) {
        return String.join(separator, parts.stream().filter(part -> !part.isBlank()).toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
