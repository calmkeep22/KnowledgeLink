package com.knowledgelink.job.application;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 재시도 횟수와 대기 시간(명세 05 7장: 최초 포함 3회, 기본 30초·최대 300초 exponential backoff + jitter).
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration base;
    private final Duration max;
    private final RandomGenerator random;

    /** random은 여러 스레드에서 쓰므로 스레드 안전해야 한다(예: {@link java.util.Random}). */
    public RetryPolicy(int maxAttempts, Duration base, Duration max, RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts는 1 이상이어야 합니다.");
        }
        if (base.isNegative() || base.isZero() || max.compareTo(base) < 0) {
            throw new IllegalArgumentException("재시도 대기는 0보다 크고 최대값이 기본값 이상이어야 합니다.");
        }
        this.maxAttempts = maxAttempts;
        this.base = base;
        this.max = max;
        this.random = Objects.requireNonNull(random, "random");
    }

    /** attemptsUsed번 실행했을 때 한 번 더 실행할 수 있는지. */
    public boolean hasAttemptsLeft(int attemptsUsed) {
        return attemptsUsed < maxAttempts;
    }

    /**
     * attemptsUsed번째 실행이 실패한 뒤 기다릴 시간. 상한을 기본값부터 2배씩 늘리고(최대값까지),
     * 실패한 작업들이 한꺼번에 몰리지 않도록 [상한/2, 상한]에서 고른다. notBefore(Retry-After 등)가 더 길면 그만큼 기다린다.
     */
    public Duration delayAfter(int attemptsUsed, Duration notBefore) {
        int exponent = Math.min(Math.max(attemptsUsed, 1) - 1, 20);
        long capMillis = Math.min(max.toMillis(), base.toMillis() << exponent);
        long halfMillis = capMillis / 2;
        Duration backoff = Duration.ofMillis(halfMillis + random.nextLong(capMillis - halfMillis + 1));
        return notBefore != null && notBefore.compareTo(backoff) > 0 ? notBefore : backoff;
    }
}
