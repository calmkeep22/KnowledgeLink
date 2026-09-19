package com.knowledgelink.demo.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 유사 과거 업무 검색 응답 계약.
 *
 * @param related 검색된 과거 업무 ID별로 연결된 업무(Jira 이슈를 고친 PR, PR이 고친 이슈). 검색 결과에 없어도 담는다.
 */
public record SimilarWorkResult(
        String schemaVersion,
        String query,
        List<SimilarWorkMatch> matches,
        List<ExperiencedMember> experiencedMembers,
        SimilarWorkExplanation explanation,
        String embeddingModel,
        Instant generatedAt,
        Map<String, List<DemoActivity>> related
) {
    public SimilarWorkResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "demo-similar-work-v1" : schemaVersion;
        matches = matches == null ? List.of() : List.copyOf(matches);
        experiencedMembers = experiencedMembers == null ? List.of() : List.copyOf(experiencedMembers);
        related = related == null ? Map.of() : Map.copyOf(related);
    }
}
