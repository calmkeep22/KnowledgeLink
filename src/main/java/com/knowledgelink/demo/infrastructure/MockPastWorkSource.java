package com.knowledgelink.demo.infrastructure;

import com.knowledgelink.demo.application.PastWorkSource;
import com.knowledgelink.demo.domain.DemoActivity;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 유사 업무 검색용 가명 과거 업무 fixture. 해결된 Jira 이슈와 병합된 PR만 담는다. */
@Component
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class MockPastWorkSource implements PastWorkSource {

    static final String RESOURCE = "demo-history.json";
    private final List<DemoActivity> pastWork;

    public MockPastWorkSource(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            this.pastWork = List.copyOf(objectMapper.readValue(input, new TypeReference<>() { }));
        } catch (IOException e) {
            throw new IllegalStateException("과거 업무 fixture를 읽을 수 없습니다.", e);
        }
    }

    @Override
    public List<DemoActivity> findAll() {
        return pastWork;
    }
}
