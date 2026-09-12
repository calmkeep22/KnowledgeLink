package com.knowledgelink.common.id;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * 단조 증가 UUIDv7 생성기(RFC 9562 6.2 Method 2, monotonic random).
 *
 * <pre>
 * unix_ts_ms(48) | ver(4)=7 | rand_a(12) | var(2)=10 | rand_b(62)
 * </pre>
 *
 * <p>rand_a·rand_b 74비트를 하나의 난수 필드로 보고, 같은 밀리초 안에서는 이전 값에 무작위 양수(1~2^32)를 더한다.
 * <ul>
 *   <li>한 JVM 안에서 발급 순서가 곧 정렬 순서다. B-tree의 오른쪽 끝에만 삽입되어 리프가 촘촘히 찬다.</li>
 *   <li>+1이 아니라 무작위 간격이라 같은 밀리초의 다음 ID도 약 2^32 범위에서 추측해야 한다.</li>
 *   <li>시계가 뒤로 가면 마지막 시각을 유지한 채 값을 계속 늘린다.</li>
 * </ul>
 * 서버가 여러 대면 서버 사이의 순서는 밀리초 단위로만 맞는다.
 */
final class UuidV7Generator {

    private static final int RAND_A_BITS = 12;
    private static final long RAND_A_LIMIT = 1L << RAND_A_BITS;
    private static final long RAND_B_MASK = (1L << 62) - 1;
    private static final long MAX_INCREMENT = 1L << 32;

    private final LongSupplier epochMillis;
    private final RandomGenerator random;

    private long lastMillis = Long.MIN_VALUE;
    private long randA;
    private long randB;

    UuidV7Generator(LongSupplier epochMillis, RandomGenerator random) {
        this.epochMillis = epochMillis;
        this.random = random;
    }

    synchronized UUID next() {
        long now = epochMillis.getAsLong();
        if (now > lastMillis) {
            lastMillis = now;
            reseed();
        } else {
            increment();
        }
        long mostSigBits = (lastMillis << 16) | 0x7000L | randA;
        long leastSigBits = 0x8000_0000_0000_0000L | randB;
        return new UUID(mostSigBits, leastSigBits);
    }

    /** rand_a 최상위 비트를 0으로 시작해 같은 밀리초 안에서 늘어날 여유를 남긴다(RFC 9562 6.2 rollover guard). */
    private void reseed() {
        randA = random.nextLong(RAND_A_LIMIT >> 1);
        randB = random.nextLong() & RAND_B_MASK;
    }

    private void increment() {
        randB += 1 + random.nextLong(MAX_INCREMENT);
        if (randB > RAND_B_MASK) {
            randB &= RAND_B_MASK;
            randA++;
            if (randA >= RAND_A_LIMIT) {
                // 한 밀리초에 74비트를 다 쓴 경우(사실상 도달하지 않음): 다음 밀리초를 미리 쓴다.
                lastMillis++;
                reseed();
            }
        }
    }
}
