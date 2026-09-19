package com.knowledgelink.demo.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import com.knowledgelink.demo.domain.PastWorkSourceInfo.ExampleQuery;
import com.knowledgelink.demo.domain.SimilarWorkExplanation;
import com.knowledgelink.demo.domain.SummaryPoint;
import com.knowledgelink.demo.domain.WorkSummary;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DemoCacheWarmerTest {

    private final List<DemoActivity> pastWork = List.of(
            activity("p-1", "project-x", "member-x", "결제 중복"),
            activity("p-2", "project-x", "member-y", "세션 만료"));
    private final List<DemoActivity> activities = List.of(
            activity("a-1", "project-a", "member-a", "로그인 정책"),
            activity("a-2", "project-a", "member-b", "세션 필터"),
            activity("a-3", "project-b", "member-a", "검색 튜닝"));

    @Test
    void 예시_질문과_모든_팀원과_프로젝트_요약을_한_번씩_호출해_캐시를_채운다() {
        AtomicInteger explainCalls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        SimilarWorkService similar = similarService(explainCalls, List.of(
                new ExampleQuery("결제", "결제 중복"), new ExampleQuery("세션", "세션 만료")));
        DemoSummaryService summaries = new DemoSummaryService(() -> activities, request -> {
            summaryCalls.incrementAndGet();
            return summary(request);
        });
        DemoCacheWarmer warmer = new DemoCacheWarmer(similar, summaries);

        int warmed = warmer.warm();

        // 예시 2 + 팀원 2 + 프로젝트 2 × (PROJECT, HANDOFF)
        assertEquals(8, warmed);
        assertEquals(2, explainCalls.get());
        assertEquals(6, summaryCalls.get());

        similar.search("결제 중복");
        summaries.summarizeMember("member-a");
        assertEquals(2, explainCalls.get());
        assertEquals(6, summaryCalls.get());
    }

    @Test
    void 한_항목이_실패해도_나머지를_계속_채운다() {
        AtomicInteger summaryCalls = new AtomicInteger();
        SimilarWorkService similar = similarService(new AtomicInteger(), List.of(new ExampleQuery("결제", "결제 중복")));
        DemoSummaryService summaries = new DemoSummaryService(() -> activities, request -> {
            if (summaryCalls.incrementAndGet() == 1) {
                throw new ActivitySummaryGenerationException("일시 실패");
            }
            return summary(request);
        });

        int warmed = new DemoCacheWarmer(similar, summaries).warm();

        assertEquals(6, warmed);
        assertEquals(6, summaryCalls.get());
    }

    private SimilarWorkService similarService(AtomicInteger explainCalls, List<ExampleQuery> examples) {
        PastWorkSource source = new PastWorkSource() {
            @Override
            public List<DemoActivity> findAll() {
                return pastWork;
            }

            @Override
            public PastWorkSourceInfo info() {
                return PastWorkSourceInfo.of("테스트", "mock", null, pastWork, links(), examples);
            }
        };
        TextEmbedder embedder = new TextEmbedder() {
            @Override
            public float[] embed(String text) {
                return text.startsWith("결제") ? new float[] {1, 0} : new float[] {0, 1};
            }

            @Override
            public String modelId() {
                return "test";
            }
        };
        SimilarWorkExplainer explainer = request -> {
            explainCalls.incrementAndGet();
            String id = request.matches().getFirst().activity().id();
            return new SimilarWorkExplanation("비슷함", List.of(new SummaryPoint("근거", List.of(id))), List.of(), "test");
        };
        return new SimilarWorkService(source, embedder, explainer, new SimilarWorkProperties(1, 20, 10));
    }

    private static WorkSummary summary(SummaryGenerationRequest request) {
        return new WorkSummary("demo-summary-v1", request.mode(), request.subjectId(), request.subjectName(),
                List.of(new SummaryPoint("완료", List.of(request.activities().getFirst().id()))),
                List.of(), List.of(), List.of(), Instant.parse("2026-09-19T00:00:00Z"), "test");
    }

    private static DemoActivity activity(String id, String projectId, String memberId, String title) {
        return new DemoActivity(id, projectId, memberId, memberId + " 이름", ActivityKind.JIRA_ISSUE, title, "DONE",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.invalid/" + id, "");
    }
}
