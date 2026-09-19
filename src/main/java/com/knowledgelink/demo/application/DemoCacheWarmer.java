package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import com.knowledgelink.demo.domain.SummaryMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 직후 백그라운드에서 유사 업무 색인을 만들고, 출처별 예시 질문과 모든 팀원·프로젝트 요약을 한 번씩 호출해
 * 캐시를 채운다. 재시작 뒤 처음 누르는 사람이 AI 응답을 기다리지 않게 하기 위해서다.
 * 하나가 실패해도 나머지는 계속하며, 실패한 항목은 처음 요청할 때 다시 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class DemoCacheWarmer {

    private final SimilarWorkService similarWorkService;
    private final DemoSummaryService summaryService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread thread = new Thread(this::warm, "demo-cache-warmer");
        thread.setDaemon(true);
        thread.start();
    }

    /** 채운 항목 수를 돌려준다. 테스트에서 동기로 부른다. */
    int warm() {
        long started = System.nanoTime();
        similarWorkService.warmUp();
        int warmed = 0;

        PastWorkSourceInfo info = similarWorkService.sourceInfo();
        for (PastWorkSourceInfo.ExampleQuery example : info.examples()) {
            warmed += attempt("example", example.label(), () -> similarWorkService.search(example.query()));
        }

        List<DemoActivity> activities = summaryService.activities();
        for (String memberId : activities.stream().map(DemoActivity::memberId).distinct().toList()) {
            warmed += attempt("member summary", memberId, () -> summaryService.summarizeMember(memberId));
        }
        for (String projectId : activities.stream().map(DemoActivity::projectId).distinct().toList()) {
            warmed += attempt("project summary", projectId, () -> summaryService.summarizeProject(projectId, SummaryMode.PROJECT));
            warmed += attempt("handoff summary", projectId, () -> summaryService.summarizeProject(projectId, SummaryMode.HANDOFF));
        }
        log.info("Demo cache warmed: items={}, elapsedMs={}", warmed, (System.nanoTime() - started) / 1_000_000);
        return warmed;
    }

    private static int attempt(String kind, String subject, Runnable call) {
        try {
            call.run();
            return 1;
        } catch (RuntimeException exception) {
            log.warn("Demo cache warm-up skipped {} {}: {}", kind, subject, exception.getMessage());
            return 0;
        }
    }
}
