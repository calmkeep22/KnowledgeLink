package com.knowledgelink.demo.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SimilarWorkExplanation;
import com.knowledgelink.demo.domain.SimilarWorkMatch;
import com.knowledgelink.demo.domain.SimilarWorkResult;
import com.knowledgelink.demo.domain.SummaryPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SimilarWorkServiceTest {

    private static final Map<String, float[]> VECTORS = Map.of(
            "결제 중복", new float[] {1, 0, 0},
            "결제 멱등성 PR", new float[] {0.9f, 0.1f, 0},
            "세션 만료", new float[] {0, 1, 0},
            "검색 인덱스", new float[] {0, 0, 1},
            "결제가 두 번 됨", new float[] {1, 0.05f, 0},
            "로그인 풀림", new float[] {0, 1, 0.05f});

    private final List<DemoActivity> pastWork = List.of(
            activity("p-1", "member-a", "김하늘", "결제 중복"),
            activity("p-2", "member-a", "김하늘", "결제 멱등성 PR"),
            activity("p-3", "member-b", "이로운", "세션 만료"),
            activity("p-4", "member-c", "박새봄", "검색 인덱스"));

    private final AtomicInteger embedCalls = new AtomicInteger();
    private final AtomicInteger explainCalls = new AtomicInteger();

    @Test
    void 코사인_유사도가_높은_과거_업무부터_topK만_설명기에_넘긴다() {
        SimilarWorkService service = service(2, 20, evidenceOfTop());

        SimilarWorkResult result = service.search("결제가 두 번 됨");

        assertEquals(List.of("p-1", "p-2"), result.matches().stream().map(match -> match.activity().id()).toList());
        assertEquals("fake-test", result.embeddingModel());
        assertEquals("결제가 두 번 됨", result.query());
    }

    @Test
    void 경험_있는_팀원은_관련_결과만_담당자별로_합산한다() {
        SimilarWorkService service = service(3, 20, evidenceOfTop());

        SimilarWorkResult result = service.search("결제가 두 번 됨");

        // 세 번째 결과(세션 만료, 유사도 약 0.05)는 1위의 70%에 못 미쳐 담당자 집계에서 빠진다.
        assertEquals("p-3", result.matches().get(2).activity().id());
        assertEquals(1, result.experiencedMembers().size());
        assertEquals("member-a", result.experiencedMembers().getFirst().memberId());
        assertEquals(List.of("p-1", "p-2"), result.experiencedMembers().getFirst().evidenceIds());
    }

    @Test
    void 검색_결과_밖의_근거를_인용하면_503으로_거절한다() {
        SimilarWorkService service = service(1, 20, request -> explanation("p-4"));

        ApiException exception = assertThrows(ApiException.class, () -> service.search("결제가 두 번 됨"));

        assertEquals(ErrorCode.TEMPORARY_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void 같은_질의는_공백이_달라도_공급자를_다시_호출하지_않는다() {
        SimilarWorkService service = service(2, 20, evidenceOfTop());

        SimilarWorkResult first = service.search("결제가 두 번 됨");
        int embedCallsAfterFirst = embedCalls.get();
        SimilarWorkResult second = service.search("  결제가   두 번 됨 ");

        assertSame(first, second);
        assertEquals(embedCallsAfterFirst, embedCalls.get());
        assertEquals(1, explainCalls.get());
    }

    @Test
    void 분당_한도를_넘은_새_질의는_503이고_캐시된_질의와_다음_창은_허용한다() {
        AtomicLong now = new AtomicLong();
        SimilarWorkService service = new SimilarWorkService(
                () -> pastWork, embedder(), explainer(evidenceOfTop()),
                new SimilarWorkProperties(2, 1, 10), now::get);

        SimilarWorkResult first = service.search("결제가 두 번 됨");
        ApiException exception = assertThrows(ApiException.class, () -> service.search("로그인 풀림"));
        assertEquals(ErrorCode.TEMPORARY_UNAVAILABLE, exception.getErrorCode());
        assertSame(first, service.search("결제가 두 번 됨"));

        now.addAndGet(Duration.ofMinutes(1).toNanos());
        assertEquals("p-3", service.search("로그인 풀림").matches().getFirst().activity().id());
    }

    @Test
    void 색인_실패는_503이고_다음_검색에서_다시_색인한다() {
        AtomicInteger failuresLeft = new AtomicInteger(1);
        TextEmbedder flaky = new TextEmbedder() {
            @Override
            public float[] embed(String text) {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new ActivitySummaryGenerationException("일시 실패");
                }
                return VECTORS.get(text);
            }

            @Override
            public String modelId() {
                return "flaky";
            }
        };
        SimilarWorkService service = new SimilarWorkService(
                () -> pastWork, flaky, explainer(evidenceOfTop()), new SimilarWorkProperties(2, 20, 10), () -> 0L);

        ApiException exception = assertThrows(ApiException.class, () -> service.search("결제가 두 번 됨"));
        assertEquals(ErrorCode.TEMPORARY_UNAVAILABLE, exception.getErrorCode());

        assertEquals("p-1", service.search("결제가 두 번 됨").matches().getFirst().activity().id());
    }

    @Test
    void 너무_짧거나_긴_질의는_공급자_호출_전에_400으로_거절한다() {
        SimilarWorkService service = service(2, 20, evidenceOfTop());

        ApiException tooShort = assertThrows(ApiException.class, () -> service.search(" 결 "));
        ApiException tooLong = assertThrows(ApiException.class, () -> service.search("가".repeat(501)));

        assertEquals(ErrorCode.INVALID_REQUEST, tooShort.getErrorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, tooLong.getErrorCode());
        assertEquals(0, embedCalls.get());
    }

    @Test
    void 코사인_유사도는_벡터_크기와_무관하다() {
        assertEquals(1.0, SimilarWorkService.cosine(new float[] {1, 1}, new float[] {3, 3}), 1e-9);
        assertEquals(0.0, SimilarWorkService.cosine(new float[] {1, 0}, new float[] {0, 2}), 1e-9);
        assertEquals(0.0, SimilarWorkService.cosine(new float[] {0, 0}, new float[] {1, 0}), 1e-9);
        assertThrows(ActivitySummaryGenerationException.class,
                () -> SimilarWorkService.cosine(new float[] {1}, new float[] {1, 0}));
    }

    private SimilarWorkService service(int topK, int perMinute, Function<SimilarWorkRequest, SimilarWorkExplanation> explain) {
        return new SimilarWorkService(() -> pastWork, embedder(), explainer(explain),
                new SimilarWorkProperties(topK, perMinute, 10), () -> 0L);
    }

    private TextEmbedder embedder() {
        return new TextEmbedder() {
            @Override
            public float[] embed(String text) {
                embedCalls.incrementAndGet();
                float[] vector = VECTORS.get(text);
                if (vector == null) {
                    throw new AssertionError("unexpected embedding input: " + text);
                }
                return vector;
            }

            @Override
            public String modelId() {
                return "fake-test";
            }
        };
    }

    private SimilarWorkExplainer explainer(Function<SimilarWorkRequest, SimilarWorkExplanation> explain) {
        return request -> {
            explainCalls.incrementAndGet();
            return explain.apply(request);
        };
    }

    private static Function<SimilarWorkRequest, SimilarWorkExplanation> evidenceOfTop() {
        return request -> {
            SimilarWorkMatch top = request.matches().getFirst();
            return explanation(top.activity().id());
        };
    }

    private static SimilarWorkExplanation explanation(String evidenceId) {
        return new SimilarWorkExplanation(
                "비슷한 과거 업무가 있습니다.",
                List.of(new SummaryPoint("비슷한 점", List.of(evidenceId))),
                List.of(new SummaryPoint("참고할 점", List.of(evidenceId))),
                "test");
    }

    private static DemoActivity activity(String id, String memberId, String memberName, String title) {
        return new DemoActivity(id, "project-a", memberId, memberName, ActivityKind.JIRA_ISSUE, title, "DONE",
                Instant.parse("2026-01-01T00:00:00Z"), "https://example.invalid/" + id, "");
    }
}
