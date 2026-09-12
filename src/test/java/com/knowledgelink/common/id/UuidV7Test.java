package com.knowledgelink.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    private static final long MILLIS = 1_757_600_000_000L;

    private static long timestampOf(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }

    @Test
    void 버전_7과_RFC_변형_비트를_가진다() {
        UUID id = UuidV7.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void 상위_48비트에_발급_시각을_담는다() {
        UuidV7Generator generator = new UuidV7Generator(() -> MILLIS, new Random(1));

        assertThat(timestampOf(generator.next())).isEqualTo(MILLIS);
    }

    @Test
    void 같은_밀리초_안에서도_발급_순서대로_커진다() {
        UuidV7Generator generator = new UuidV7Generator(() -> MILLIS, new Random(1));

        UUID previous = generator.next();
        for (int i = 0; i < 100_000; i++) {
            UUID current = generator.next();
            assertThat(current).isGreaterThan(previous);
            assertThat(timestampOf(current)).isEqualTo(MILLIS);
            previous = current;
        }
    }

    @Test
    void 같은_밀리초의_다음_ID는_1씩_늘지_않아_추측하기_어렵다() {
        UuidV7Generator generator = new UuidV7Generator(() -> MILLIS, new Random(1));

        UUID first = generator.next();
        UUID second = generator.next();

        assertThat(second.getLeastSignificantBits() - first.getLeastSignificantBits()).isGreaterThan(1);
    }

    @Test
    void 시계가_뒤로_가도_작아지지_않고_마지막_시각을_유지한다() {
        AtomicLong clock = new AtomicLong(MILLIS);
        UuidV7Generator generator = new UuidV7Generator(clock::get, new Random(1));

        UUID before = generator.next();
        clock.set(MILLIS - 5);
        UUID after = generator.next();

        assertThat(after).isGreaterThan(before);
        assertThat(timestampOf(after)).isEqualTo(MILLIS);
    }

    @Test
    void 밀리초가_바뀌면_새_시각으로_발급한다() {
        AtomicLong clock = new AtomicLong(MILLIS);
        UuidV7Generator generator = new UuidV7Generator(clock::get, new Random(1));

        UUID first = generator.next();
        clock.incrementAndGet();
        UUID second = generator.next();

        assertThat(timestampOf(second)).isEqualTo(MILLIS + 1);
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void 대량_발급해도_충돌이_없고_발급_순서가_정렬_순서다() {
        List<UUID> issued = new ArrayList<>(100_000);
        for (int i = 0; i < 100_000; i++) {
            issued.add(UuidV7.next());
        }

        assertThat(Set.copyOf(issued)).hasSize(issued.size());
        assertThat(issued).isSorted();
    }

    @Test
    void 여러_스레드가_동시에_발급해도_중복이_없다() throws Exception {
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        ids.add(UuidV7.next());
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(ids).hasSize(8 * 50_000);
    }
}
