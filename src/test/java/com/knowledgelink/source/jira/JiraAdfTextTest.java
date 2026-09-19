package com.knowledgelink.source.jira;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JiraAdfTextTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 비어_있거나_없는_문서는_null이다() {
        assertThat(JiraAdfText.toText(null)).isNull();
        assertThat(JiraAdfText.toText(json.readTree("null"))).isNull();
        assertThat(JiraAdfText.toText(adf("{\"type\":\"paragraph\"}"))).isNull();
    }

    @Test
    void 중첩_목록과_순서_목록은_들여쓰기와_번호를_유지한다() {
        String text = JiraAdfText.toText(adf("""
                {"type":"orderedList","attrs":{"order":3},"content":[
                  {"type":"listItem","content":[
                    {"type":"paragraph","content":[{"type":"text","text":"원인 확인"}]},
                    {"type":"bulletList","content":[
                      {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"로그"}]}]}]}]},
                  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"수정"}]}]}]}
                """));

        assertThat(text).isEqualTo("3. 원인 확인\n  - 로그\n4. 수정");
    }

    @Test
    void 표_인용_줄바꿈_할일을_읽을_수_있는_평문으로_옮긴다() {
        String text = JiraAdfText.toText(adf("""
                {"type":"table","content":[
                  {"type":"tableRow","content":[
                    {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"코드"}]}]},
                    {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"의미"}]}]}]},
                  {"type":"tableRow","content":[
                    {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"E01"}]}]},
                    {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"한도 초과"}]}]}]}]},
                {"type":"blockquote","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"첫 줄"},{"type":"hardBreak"},{"type":"text","text":"둘째 줄"}]}]},
                {"type":"taskList","content":[
                  {"type":"taskItem","attrs":{"state":"DONE"},"content":[{"type":"text","text":"배포"}]},
                  {"type":"taskItem","attrs":{"state":"TODO"},"content":[{"type":"text","text":"모니터링"}]}]}
                """));

        assertThat(text).isEqualTo("""
                코드 | 의미
                E01 | 한도 초과

                > 첫 줄
                > 둘째 줄

                - [x] 배포
                - [ ] 모니터링""");
    }

    @Test
    void 카드_링크는_주소를_남기고_텍스트와_같은_링크는_한_번만_적는다() {
        String text = JiraAdfText.toText(adf("""
                {"type":"paragraph","content":[
                  {"type":"inlineCard","attrs":{"url":"https://github.com/org/repo/pull/7"}},
                  {"type":"text","text":" "},
                  {"type":"text","text":"https://example.com","marks":[{"type":"link","attrs":{"href":"https://example.com"}}]}]}
                """));

        assertThat(text).isEqualTo("https://github.com/org/repo/pull/7 https://example.com");
    }

    @Test
    void 모르는_노드도_안쪽_내용은_버리지_않는다() {
        String text = JiraAdfText.toText(adf("""
                {"type":"panel","attrs":{"panelType":"warning"},"content":[
                  {"type":"paragraph","content":[{"type":"text","text":"운영 DB 주의"}]}]},
                {"type":"futureInlineThing","content":[{"type":"text","text":"새 노드"}]}
                """));

        assertThat(text).isEqualTo("운영 DB 주의\n\n새 노드");
    }

    private JsonNode adf(String content) {
        return json.readTree("{\"type\":\"doc\",\"version\":1,\"content\":[" + content + "]}");
    }
}
