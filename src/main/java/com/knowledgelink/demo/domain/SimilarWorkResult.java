package com.knowledgelink.demo.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 유사 과거 업무 검색 응답 계약.
 *
 * @param expandedQuery 임베딩에 함께 쓴 확장 검색어. 확장하지 않았거나 실패했으면 null이다.
 * @param lowRelevance 1위 유사도가 기준보다 낮아 비슷한 사례가 없다고 판단했는지. 이때는 설명 모델을 부르지 않는다.
 * @param explanation 검색 결과만 근거로 만든 설명. 아직 만들지 않았으면 null이고 설명 API로 따로 받는다.
 * @param explanationPending 설명을 아직 만들지 않았는지. explanation이 null이면 true다.
 * @param related 검색된 과거 업무 ID별로 연결된 업무(Jira 이슈를 고친 PR, PR이 고친 이슈). 검색 결과에 없어도 담는다.
 */
public record SimilarWorkResult(
        String schemaVersion,
        String query,
        String expandedQuery,
        boolean lowRelevance,
        List<SimilarWorkMatch> matches,
        List<ExperiencedMember> experiencedMembers,
        SimilarWorkExplanation explanation,
        boolean explanationPending,
        String embeddingModel,
        Instant generatedAt,
        Map<String, List<DemoActivity>> related
) {
    public SimilarWorkResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "demo-similar-work-v1" : schemaVersion;
        matches = matches == null ? List.of() : List.copyOf(matches);
        experiencedMembers = experiencedMembers == null ? List.of() : List.copyOf(experiencedMembers);
        explanationPending = explanation == null;
        related = related == null ? Map.of() : Map.copyOf(related);
    }

    public SimilarWorkResult withExplanation(SimilarWorkExplanation value) {
        return new SimilarWorkResult(schemaVersion, query, expandedQuery, lowRelevance, matches, experiencedMembers,
                value, value == null, embeddingModel, generatedAt, related);
    }
}
