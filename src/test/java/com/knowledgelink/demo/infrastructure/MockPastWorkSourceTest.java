package com.knowledgelink.demo.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.domain.DemoActivity;
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
}
