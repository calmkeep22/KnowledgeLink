package com.knowledgelink.demo.infrastructure;

import com.knowledgelink.demo.application.ActivitySource;
import com.knowledgelink.demo.domain.DemoActivity;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 데모 전용 가명 fixture. 운영 원본 모델이나 DB에는 접근하지 않는다. */
@Component
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class MockActivitySource implements ActivitySource {

    private static final String RESOURCE = "demo-activities.json";
    private final List<DemoActivity> activities;

    public MockActivitySource(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            this.activities = List.copyOf(objectMapper.readValue(input, new TypeReference<>() { }));
        } catch (IOException e) {
            throw new IllegalStateException("데모 활동 fixture를 읽을 수 없습니다.", e);
        }
    }

    @Override
    public List<DemoActivity> findAll() {
        return activities;
    }
}
