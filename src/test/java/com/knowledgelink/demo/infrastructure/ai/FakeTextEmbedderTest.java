package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.SimilarWorkExplainer;
import com.knowledgelink.demo.application.SimilarWorkProperties;
import com.knowledgelink.demo.application.SimilarWorkService;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SimilarWorkResult;
import com.knowledgelink.demo.infrastructure.MockPastWorkSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FakeTextEmbedderTest {
    private final FakeTextEmbedder embedder = new FakeTextEmbedder();

    @Test
    void 같은_텍스트는_같은_정규화_벡터가_된다() {
        float[] first = embedder.embed("결제 버튼 중복 클릭");
        float[] second = embedder.embed("결제 버튼 중복 클릭");

        assertArrayEquals(first, second);
        double norm = 0;
        for (float value : first) {
            norm += value * value;
        }
        assertEquals(1.0, norm, 1e-5);
    }

    @Test
    void 어휘가_겹치는_텍스트가_관련_없는_텍스트보다_가깝다() {
        float[] query = embedder.embed("결제가 두 번 처리돼요");
        double related = dot(query, embedder.embed("결제 요청이 중복 처리됨"));
        double unrelated = dot(query, embedder.embed("이미지 업로드 용량 제한"));

        assertTrue(related > unrelated, "related=" + related + ", unrelated=" + unrelated);
    }

    @Test
    void 빈_텍스트는_영벡터다() {
        float[] vector = embedder.embed("  !!  ");

        assertEquals(FakeTextEmbedder.DIMENSIONS, vector.length);
        for (float value : vector) {
            assertEquals(0f, value);
        }
    }

    /** 발표용 예시 질의가 fake 모드에서도 의도한 과거 업무를 1순위로 찾는지 실제 fixture로 확인한다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "결제 버튼을 두 번 누르면 주문이 두 번 생성돼요 | past-0101,past-0102",
            "서버를 늘린 뒤 로그인이 자꾸 풀린다는 문의가 들어옴 | past-0201,past-0202",
            "엑셀 다운로드가 너무 오래 걸리다가 타임아웃 남 | past-0401,past-0402",
            "로그에 개인정보가 그대로 찍히는 것 같아요 | past-1701,past-1702"
    })
    void 발표_예시_질의는_의도한_과거_업무를_가장_먼저_찾는다(String query, String expectedIds) {
        List<DemoActivity> pastWork = new MockPastWorkSource(new ObjectMapper()).findAll();
        SimilarWorkExplainer explainer = new FakeSimilarWorkExplainer();
        SimilarWorkService service = new SimilarWorkService(
                () -> pastWork, embedder, explainer, new SimilarWorkProperties(5, 20, 10));

        SimilarWorkResult result = service.search(query);

        Set<String> expected = Set.of(expectedIds.split(","));
        assertTrue(expected.contains(result.matches().getFirst().activity().id()),
                () -> query + " -> " + result.matches().stream()
                        .map(match -> match.activity().id() + "=" + match.score()).toList());
    }

    private static double dot(float[] left, float[] right) {
        double sum = 0;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }
}
