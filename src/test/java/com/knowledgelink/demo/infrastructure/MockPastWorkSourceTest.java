package com.knowledgelink.demo.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo.ExampleQuery;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MockPastWorkSourceTest {

    @Test
    void 과거_업무는_해결된_이슈와_병합된_PR이고_현재_활동과_ID가_겹치지_않는다() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<DemoActivity> pastWork = new MockPastWorkSource(objectMapper).findAll();
        Set<String> currentIds = new MockActivitySource(objectMapper).findAll().stream()
                .map(DemoActivity::id)
                .collect(Collectors.toSet());

        assertTrue(pastWork.size() >= 30);
        assertEquals(pastWork.size(), pastWork.stream().map(DemoActivity::id).distinct().count());
        assertTrue(pastWork.stream().allMatch(activity -> Set.of("DONE", "MERGED").contains(activity.status())));
        assertTrue(pastWork.stream().noneMatch(activity -> currentIds.contains(activity.id())));
        assertTrue(pastWork.stream().allMatch(activity -> activity.sourceUrl().startsWith("https://example.invalid/")));
    }

    @Test
    void 같은_문제를_다룬_이슈와_PR을_짝으로_연결하고_예시_질문을_출처_정보에_싣는다() {
        MockPastWorkSource source = new MockPastWorkSource(new ObjectMapper(),
                List.of(new ExampleQuery("결제 중복", "결제가 두 번 돼요")));

        assertEquals(List.of("past-0102"), source.links().get("past-0101"));
        assertEquals(List.of("past-0101"), source.links().get("past-0102"));
        assertEquals("mock", source.info().sourceType());
        assertEquals(17, source.info().linkedIssueCount());
        assertEquals("결제 중복", source.info().examples().getFirst().label());
    }
}
