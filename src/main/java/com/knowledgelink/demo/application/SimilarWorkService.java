package com.knowledgelink.demo.application;

import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.ExperiencedMember;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import com.knowledgelink.demo.domain.SimilarWorkExplanation;
import com.knowledgelink.demo.domain.SimilarWorkMatch;
import com.knowledgelink.demo.domain.SimilarWorkResult;
import com.knowledgelink.demo.domain.SummaryPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 새 업무 설명과 비슷한 과거 업무를 임베딩 코사인 유사도로 찾고, 찾은 자료만 근거로 설명을 붙인다.
 * 과거 업무 벡터는 DB 없이 메모리에 둔다. 데모 fixture가 수십 건이라 전수 비교로 충분하다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class SimilarWorkService {

    static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_EXPERIENCED_MEMBERS = 3;
    private static final double RELEVANT_SCORE_RATIO = 0.7;
    /** 담당자가 없는 원본 이슈에 쓰는 memberId. 관련 팀원 집계에서 뺀다. */
    public static final String UNASSIGNED_MEMBER_ID = "unassigned";

    private final PastWorkSource pastWorkSource;
    private final TextEmbedder embedder;
    private final SimilarWorkExplainer explainer;
    private final SimilarWorkProperties properties;
    private final RequestBudget budget;
    private final Map<String, SimilarWorkResult> cache;

    private volatile List<IndexedWork> index;

    @Autowired
    public SimilarWorkService(PastWorkSource pastWorkSource,
                              TextEmbedder embedder,
                              SimilarWorkExplainer explainer,
                              SimilarWorkProperties properties) {
        this(pastWorkSource, embedder, explainer, properties, System::nanoTime);
    }

    SimilarWorkService(PastWorkSource pastWorkSource,
                       TextEmbedder embedder,
                       SimilarWorkExplainer explainer,
                       SimilarWorkProperties properties,
                       LongSupplier nanoClock) {
        this.pastWorkSource = pastWorkSource;
        this.embedder = embedder;
        this.explainer = explainer;
        this.properties = properties;
        this.budget = new RequestBudget(properties.maxAiRequestsPerMinute(), nanoClock);
        this.cache = boundedCache(properties.cacheSize());
    }

    /** 첫 검색이 색인 시간까지 기다리지 않도록 기동 직후 미리 만든다. 실패해도 첫 검색에서 다시 시도한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            ensureIndex();
            log.info("Similar work index ready: items={}, model={}", index.size(), embedder.modelId());
        } catch (RuntimeException exception) {
            log.warn("Similar work index warm-up failed; will retry on first search", exception);
        }
    }

    public SimilarWorkResult search(String rawQuery) {
        String query = normalize(rawQuery);
        SimilarWorkResult cached = cachedResult(query);
        if (cached != null) {
            return cached;
        }
        if (!budget.tryAcquire()) {
            log.warn("Similar work request budget exhausted: limit={}/min", properties.maxAiRequestsPerMinute());
            throw new ApiException(ErrorCode.TEMPORARY_UNAVAILABLE);
        }
        try {
            SimilarWorkResult result = searchUncached(query);
            synchronized (cache) {
                cache.putIfAbsent(query, result);
            }
            return result;
        } catch (ActivitySummaryGenerationException exception) {
            log.warn("Similar work search failed: queryLength={}", query.length(), exception);
            throw new ApiException(ErrorCode.TEMPORARY_UNAVAILABLE);
        }
    }

    private SimilarWorkResult searchUncached(String query) {
        List<IndexedWork> indexed = ensureIndex();
        float[] queryVector = embedder.embed(query);

        List<SimilarWorkMatch> matches = indexed.stream()
                .map(work -> new SimilarWorkMatch(work.activity(), round(cosine(queryVector, work.vector()))))
                .sorted(Comparator.comparingDouble(SimilarWorkMatch::score).reversed()
                        .thenComparing(match -> match.activity().id()))
                .limit(properties.topK())
                .toList();

        SimilarWorkExplanation explanation = explainer.explain(new SimilarWorkRequest(query, matches));
        validateEvidence(explanation, matches);

        return new SimilarWorkResult(
                "demo-similar-work-v1",
                query,
                matches,
                experiencedMembers(matches),
                explanation,
                embedder.modelId(),
                Instant.now(),
                relatedWork(matches, indexed));
    }

    public PastWorkSourceInfo sourceInfo() {
        return pastWorkSource.info();
    }

    /** 검색된 이슈를 고친 PR, 검색된 PR이 고친 이슈를 붙인다. 연결 상대가 검색 결과에 없어도 보여 준다. */
    private Map<String, List<DemoActivity>> relatedWork(List<SimilarWorkMatch> matches, List<IndexedWork> indexed) {
        Map<String, List<String>> links = pastWorkSource.links();
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<String, DemoActivity> byId = indexed.stream()
                .collect(Collectors.toMap(work -> work.activity().id(), IndexedWork::activity, (first, second) -> first));
        Map<String, List<DemoActivity>> related = new LinkedHashMap<>();
        for (SimilarWorkMatch match : matches) {
            List<DemoActivity> linked = links.getOrDefault(match.activity().id(), List.of()).stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (!linked.isEmpty()) {
                related.put(match.activity().id(), linked);
            }
        }
        return related;
    }

    private List<IndexedWork> ensureIndex() {
        List<IndexedWork> current = index;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index == null) {
                List<DemoActivity> pastWork = pastWorkSource.findAll();
                if (pastWork.isEmpty()) {
                    throw new ActivitySummaryGenerationException("검색할 과거 업무가 없습니다.");
                }
                index = pastWork.stream()
                        .map(activity -> new IndexedWork(activity, embedder.embed(embeddingText(activity))))
                        .toList();
            }
            return index;
        }
    }

    static String embeddingText(DemoActivity activity) {
        return activity.details().isEmpty() ? activity.title() : activity.title() + "\n" + activity.details();
    }

    static String normalize(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip().replaceAll("\\s+", " ");
        if (query.length() < 2) {
            throw ApiException.invalidField("query", "업무 설명을 2자 이상 입력해 주세요.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw ApiException.invalidField("query", "업무 설명은 " + MAX_QUERY_LENGTH + "자 이하로 입력해 주세요.");
        }
        return query;
    }

    static double cosine(float[] left, float[] right) {
        if (left.length != right.length || left.length == 0) {
            throw new ActivitySummaryGenerationException("임베딩 차원이 맞지 않습니다.");
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    /**
     * 담당자별로 검색 결과 유사도를 더한다. 이번 질의에 대한 관련 경험의 근거일 뿐 사람의 성과 지표가 아니다.
     * 1위의 70%에 못 미치는 결과는 주제가 다른 자료일 가능성이 커서 담당자 집계에서 뺀다.
     * Titan 기준으로 같은 주제는 0.5~0.65, 다른 주제도 0.3 안팎이 나와 절반 기준으로는 걸러지지 않았다.
     */
    private static List<ExperiencedMember> experiencedMembers(List<SimilarWorkMatch> matches) {
        double threshold = matches.getFirst().score() * RELEVANT_SCORE_RATIO;
        Map<String, List<SimilarWorkMatch>> byMember = matches.stream()
                .filter(match -> match.score() > 0 && match.score() >= threshold)
                .filter(match -> !UNASSIGNED_MEMBER_ID.equals(match.activity().memberId()))
                .collect(Collectors.groupingBy(match -> match.activity().memberId(), LinkedHashMap::new, Collectors.toList()));
        return byMember.values().stream()
                .map(memberMatches -> new ExperiencedMember(
                        memberMatches.getFirst().activity().memberId(),
                        memberMatches.getFirst().activity().memberName(),
                        round(memberMatches.stream().mapToDouble(SimilarWorkMatch::score).sum()),
                        memberMatches.stream().map(match -> match.activity().id()).toList()))
                .sorted(Comparator.comparingDouble(ExperiencedMember::relevance).reversed()
                        .thenComparing(ExperiencedMember::memberId))
                .limit(MAX_EXPERIENCED_MEMBERS)
                .toList();
    }

    private static void validateEvidence(SimilarWorkExplanation explanation, List<SimilarWorkMatch> matches) {
        if (explanation == null) {
            throw new ActivitySummaryGenerationException("설명 생성기가 응답을 반환하지 않았습니다.");
        }
        Set<String> matchedIds = matches.stream()
                .map(match -> match.activity().id())
                .collect(Collectors.toUnmodifiableSet());
        boolean allEvidenceMatched = Stream.of(explanation.similarWork(), explanation.suggestedApproach())
                .flatMap(List::stream)
                .map(SummaryPoint::evidenceIds)
                .flatMap(List::stream)
                .allMatch(matchedIds::contains);
        if (!allEvidenceMatched) {
            throw new ActivitySummaryGenerationException("설명에 검색 결과 밖의 근거가 포함되어 있습니다.");
        }
    }

    private SimilarWorkResult cachedResult(String query) {
        synchronized (cache) {
            return cache.get(query);
        }
    }

    private static Map<String, SimilarWorkResult> boundedCache(int maxSize) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SimilarWorkResult> eldest) {
                return size() > maxSize;
            }
        };
    }

    private record IndexedWork(DemoActivity activity, float[] vector) {
    }

    /** 1분 고정 창 호출 한도. 공개 링크에서 새 질의가 몰려도 유료 호출 수를 제한한다. */
    static final class RequestBudget {
        private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

        private final int limit;
        private final LongSupplier nanoClock;
        private long windowStart;
        private int used;

        RequestBudget(int limit, LongSupplier nanoClock) {
            this.limit = limit;
            this.nanoClock = nanoClock;
            this.windowStart = nanoClock.getAsLong();
        }

        synchronized boolean tryAcquire() {
            long now = nanoClock.getAsLong();
            if (now - windowStart >= WINDOW_NANOS) {
                windowStart = now;
                used = 0;
            }
            if (used >= limit) {
                return false;
            }
            used++;
            return true;
        }
    }
}
