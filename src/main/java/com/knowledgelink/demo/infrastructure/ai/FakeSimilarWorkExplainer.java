package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.SimilarWorkExplainer;
import com.knowledgelink.demo.application.SimilarWorkRequest;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SimilarWorkExplanation;
import com.knowledgelink.demo.domain.SimilarWorkMatch;
import com.knowledgelink.demo.domain.SummaryPoint;
import java.util.List;
import java.util.Locale;

/** 네트워크와 비용 없이 검색 결과를 그대로 문장으로 옮기는 결정적 설명기. */
public final class FakeSimilarWorkExplainer implements SimilarWorkExplainer {
    private static final int MAX_POINTS = 3;
    /** fake n-gram 임베딩 기준으로, 이보다 낮으면 어휘가 우연히 조금 겹친 정도다. */
    private static final double WEAK_MATCH_SCORE = 0.2;

    @Override
    public SimilarWorkExplanation explain(SimilarWorkRequest request) {
        List<SimilarWorkMatch> top = request.matches().stream().limit(MAX_POINTS).toList();
        DemoActivity best = top.getFirst().activity();
        String score = String.format(Locale.ROOT, "%.2f", top.getFirst().score());
        String overview = top.getFirst().score() < WEAK_MATCH_SCORE
                ? "뚜렷하게 비슷한 과거 업무를 찾지 못했습니다. 가장 가까운 자료는 '" + best.title() + "'입니다 (유사도 " + score + ")."
                : "가장 비슷한 과거 업무는 '" + best.title() + "'입니다 (유사도 " + score + ").";

        List<SummaryPoint> similarWork = top.stream()
                .map(match -> point(match.activity(), "'" + match.activity().title() + "' ("
                        + kindLabel(match.activity()) + ", " + match.activity().status() + ")"))
                .toList();
        List<SummaryPoint> suggestedApproach = top.stream()
                .map(SimilarWorkMatch::activity)
                .filter(activity -> !activity.details().isEmpty())
                .map(activity -> point(activity, "'" + activity.title() + "'에서 확인된 내용: " + activity.details()))
                .toList();
        return new SimilarWorkExplanation(overview, similarWork, suggestedApproach, "fake-v1");
    }

    private static SummaryPoint point(DemoActivity activity, String text) {
        return new SummaryPoint(text, List.of(activity.id()));
    }

    private static String kindLabel(DemoActivity activity) {
        return switch (activity.kind()) {
            case JIRA_ISSUE -> "Jira 이슈";
            case GITHUB_PULL_REQUEST -> "GitHub PR";
            case GITHUB_COMMIT -> "GitHub 커밋";
        };
    }
}
