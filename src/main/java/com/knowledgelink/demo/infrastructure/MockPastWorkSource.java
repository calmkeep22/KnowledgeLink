package com.knowledgelink.demo.infrastructure;

import com.knowledgelink.demo.application.PastWorkSource;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import com.knowledgelink.demo.domain.PastWorkSourceInfo.ExampleQuery;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 유사 업무 검색용 가명 과거 업무 fixture. 해결된 Jira 이슈와 병합된 PR만 담는다.
 * ID 앞 일곱 글자가 같은 항목(past-0101 이슈와 past-0102 PR)이 같은 문제를 다룬 짝이다.
 */
public class MockPastWorkSource implements PastWorkSource {

    static final String RESOURCE = "demo-history.json";
    private static final int PAIR_PREFIX_LENGTH = "past-01".length();

    private final List<DemoActivity> pastWork;
    private final Map<String, List<String>> links;
    private final List<ExampleQuery> examples;

    public MockPastWorkSource(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    public MockPastWorkSource(ObjectMapper objectMapper, List<ExampleQuery> examples) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            this.pastWork = List.copyOf(objectMapper.readValue(input, new TypeReference<>() { }));
        } catch (IOException e) {
            throw new IllegalStateException("과거 업무 fixture를 읽을 수 없습니다.", e);
        }
        this.links = pairLinks(pastWork);
        this.examples = List.copyOf(examples);
    }

    @Override
    public List<DemoActivity> findAll() {
        return pastWork;
    }

    @Override
    public Map<String, List<String>> links() {
        return links;
    }

    @Override
    public PastWorkSourceInfo info() {
        return PastWorkSourceInfo.of("가명 예시 데이터", "mock", null, pastWork, links, examples);
    }

    private static Map<String, List<String>> pairLinks(List<DemoActivity> items) {
        Map<String, List<String>> byPrefix = new LinkedHashMap<>();
        for (DemoActivity item : items) {
            if (item.id().length() > PAIR_PREFIX_LENGTH) {
                byPrefix.computeIfAbsent(item.id().substring(0, PAIR_PREFIX_LENGTH), ignored -> new ArrayList<>()).add(item.id());
            }
        }
        Map<String, List<String>> links = new LinkedHashMap<>();
        for (List<String> group : byPrefix.values()) {
            for (String id : group) {
                List<String> others = group.stream().filter(other -> !other.equals(id)).toList();
                if (!others.isEmpty()) {
                    links.put(id, others);
                }
            }
        }
        return Map.copyOf(links);
    }
}
