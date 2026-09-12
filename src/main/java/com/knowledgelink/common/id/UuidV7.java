package com.knowledgelink.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * 애플리케이션 전역 UUIDv7 발급기. 한 JVM 안에서 발급 순서대로 커지는 값을 돌려준다.
 *
 * <p>같은 밀리초 안에서도 순서를 지켜야 B-tree 인덱스가 촘촘히 찬다. 벤치마크 기록은
 * {@code docs/benchmarks/uuid-primary-key.md}, 설계 결정은 ADR 0003.
 */
public final class UuidV7 {

    private static final UuidV7Generator GENERATOR = new UuidV7Generator(System::currentTimeMillis, new SecureRandom());

    private UuidV7() {
    }

    public static UUID next() {
        return GENERATOR.next();
    }
}
