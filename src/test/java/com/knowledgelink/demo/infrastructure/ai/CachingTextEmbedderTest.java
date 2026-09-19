package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.TextEmbedder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CachingTextEmbedderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 재시작하면_저장된_본문은_다시_부르지_않고_새_본문만_임베딩한다(@TempDir Path directory) {
        Path file = directory.resolve("cache/embeddings.json");
        RecordingEmbedder first = new RecordingEmbedder("model-a");
        List<float[]> initial = new CachingTextEmbedder(first, objectMapper, file.toString(), 4)
                .embedAll(List.of("alpha", "beta", "gamma"));

        assertEquals(Set.of("alpha", "beta", "gamma"), first.calls);
        assertTrue(Files.isRegularFile(file));

        RecordingEmbedder second = new RecordingEmbedder("model-a");
        List<float[]> restarted = new CachingTextEmbedder(second, objectMapper, file.toString(), 4)
                .embedAll(List.of("alpha", "gamma", "delta"));

        assertEquals(Set.of("delta"), second.calls);
        assertArrayEquals(initial.get(0), restarted.get(0));
        assertArrayEquals(initial.get(2), restarted.get(1));
        assertArrayEquals(RecordingEmbedder.vector("delta"), restarted.get(2));
    }

    @Test
    void 모델이_바뀌면_저장본을_쓰지_않는다(@TempDir Path directory) {
        Path file = directory.resolve("embeddings.json");
        new CachingTextEmbedder(new RecordingEmbedder("model-a"), objectMapper, file.toString(), 1).embedAll(List.of("alpha"));

        RecordingEmbedder other = new RecordingEmbedder("model-b");
        new CachingTextEmbedder(other, objectMapper, file.toString(), 1).embedAll(List.of("alpha"));

        assertEquals(Set.of("alpha"), other.calls);
    }

    @Test
    void 사용자_질의는_저장하지_않고_경로가_없으면_파일을_만들지_않는다(@TempDir Path directory) {
        Path file = directory.resolve("embeddings.json");
        RecordingEmbedder delegate = new RecordingEmbedder("model-a");
        CachingTextEmbedder caching = new CachingTextEmbedder(delegate, objectMapper, file.toString(), 2);

        caching.embed("사용자 질의");
        assertFalse(Files.exists(file));

        CachingTextEmbedder noPath = new CachingTextEmbedder(delegate, objectMapper, "", 2);
        assertEquals(2, noPath.embedAll(List.of("x", "y")).size());
        assertFalse(Files.exists(file));
    }

    private static final class RecordingEmbedder implements TextEmbedder {
        private final String model;
        private final Set<String> calls = Collections.newSetFromMap(new ConcurrentHashMap<>());

        RecordingEmbedder(String model) {
            this.model = model;
        }

        static float[] vector(String text) {
            return new float[] {text.length(), text.charAt(0)};
        }

        @Override
        public float[] embed(String text) {
            calls.add(text);
            return vector(text);
        }

        @Override
        public String modelId() {
            return model;
        }
    }
}
