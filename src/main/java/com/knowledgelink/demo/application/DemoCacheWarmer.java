package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import com.knowledgelink.demo.domain.SummaryMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 직후 백그라운드에서 유사 업무 색인을 만들고, 출처별 예시 질문과 모든 팀원·프로젝트 요약을 호출해
 * 캐시를 채운다. 재시작 뒤 처음 누르는 사람이 AI 응답을 기다리지 않게 하기 위해서다.
 * 항목들은 서로 독립이라 몇 개씩 동시에 부르고, 하나가 실패해도 나머지는 계속한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class DemoCacheWarmer {

    /** 동시 호출 수. 생성 모델의 분당 호출 한도에 여유를 남긴다. */
    static final int PARALLELISM = 4;

    private final SimilarWorkService similarWorkService;
    private final DemoSummaryService summaryService;
    private final int parallelism;

    @Autowired
    public DemoCacheWarmer(SimilarWorkService similarWorkService, DemoSummaryService summaryService) {
        this(similarWorkService, summaryService, PARALLELISM);
    }

    DemoCacheWarmer(SimilarWorkService similarWorkService, DemoSummaryService summaryService, int parallelism) {
        this.similarWorkService = similarWorkService;
        this.summaryService = summaryService;
        this.parallelism = parallelism;
    }

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

        List<Task> tasks = new ArrayList<>();
        PastWorkSourceInfo info = similarWorkService.sourceInfo();
        for (PastWorkSourceInfo.ExampleQuery example : info.examples()) {
            tasks.add(new Task("example " + example.label(), () -> similarWorkService.search(example.query())));
        }
        List<DemoActivity> activities = summaryService.activities();
        for (String memberId : activities.stream().map(DemoActivity::memberId).distinct().toList()) {
            tasks.add(new Task("member " + memberId, () -> summaryService.summarizeMember(memberId)));
        }
        for (String projectId : activities.stream().map(DemoActivity::projectId).distinct().toList()) {
            tasks.add(new Task("project " + projectId, () -> summaryService.summarizeProject(projectId, SummaryMode.PROJECT)));
            tasks.add(new Task("handoff " + projectId, () -> summaryService.summarizeProject(projectId, SummaryMode.HANDOFF)));
        }

        int warmed = run(tasks);
        log.info("Demo cache warmed: items={}/{}, elapsedMs={}", warmed, tasks.size(), (System.nanoTime() - started) / 1_000_000);
        return warmed;
    }

    private int run(List<Task> tasks) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, tasks.size())));
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Task task : tasks) {
                results.add(executor.submit(task::attempt));
            }
            int warmed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    warmed++;
                }
            }
            return warmed;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (ExecutionException exception) {
            log.warn("Demo cache warm-up stopped", exception);
            return 0;
        } finally {
            executor.shutdownNow();
        }
    }

    private record Task(String name, Runnable call) {
        boolean attempt() {
            try {
                call.run();
                return true;
            } catch (RuntimeException exception) {
                log.warn("Demo cache warm-up skipped {}: {}", name, exception.getMessage());
                return false;
            }
        }
    }
}
