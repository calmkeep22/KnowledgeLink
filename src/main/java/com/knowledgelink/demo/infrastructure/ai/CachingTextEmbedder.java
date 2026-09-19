package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.TextEmbedder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 과거 업무 색인용 임베딩을 "모델 ID + 본문" 해시로 디스크에 저장하고 재사용한다.
 * 재시작 때는 새로 생기거나 바뀐 본문만 공급자를 호출하고, 없는 것은 병렬로 임베딩한다.
 * 사용자 질의({@link #embed})는 저장하지 않는다. 공개 입력이 저장 파일을 끝없이 키우지 않게 하기 위해서다.
 */
@Slf4j
public final class CachingTextEmbedder implements TextEmbedder {

    record CacheFile(String model, Map<String, float[]> vectors) {
    }

    private final TextEmbedder delegate;
    private final ObjectMapper objectMapper;
    private final Path path;
    private final int parallelism;

    CachingTextEmbedder(TextEmbedder delegate, ObjectMapper objectMapper, String path, int parallelism) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.path = path == null || path.isBlank() ? null : Path.of(path.strip());
        this.parallelism = Math.max(1, parallelism);
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        Map<String, float[]> stored = load();
        List<String> keys = texts.stream().map(this::key).toList();
        float[][] vectors = new float[texts.size()][];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] cached = stored.get(keys.get(i));
            if (cached != null) {
                vectors[i] = cached;
            } else {
                missing.add(i);
            }
        }

        embedMissing(texts, missing, vectors);

        Map<String, float[]> current = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            current.put(keys.get(i), vectors[i]);
        }
        save(current);
        log.info("Embedding cache: model={}, reused={}, embedded={}", modelId(), texts.size() - missing.size(), missing.size());
        return List.of(vectors);
    }

    private void embedMissing(List<String> texts, List<Integer> missing, float[][] vectors) {
        if (missing.isEmpty()) {
            return;
        }
        if (parallelism == 1 || missing.size() == 1) {
            for (int index : missing) {
                vectors[index] = delegate.embed(texts.get(index));
            }
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(parallelism, missing.size()));
        try {
            List<Future<float[]>> futures = new ArrayList<>(missing.size());
            for (int index : missing) {
                String text = texts.get(index);
                futures.add(executor.submit(() -> delegate.embed(text)));
            }
            for (int i = 0; i < missing.size(); i++) {
                vectors[missing.get(i)] = futures.get(i).get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActivitySummaryGenerationException("색인 임베딩이 중단되었습니다.", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new ActivitySummaryGenerationException("색인 임베딩에 실패했습니다.", exception.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private String key(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(modelId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, float[]> load() {
        if (path == null || !Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            CacheFile file = objectMapper.readValue(path.toFile(), CacheFile.class);
            if (file.vectors() == null || !modelId().equals(file.model())) {
                return Map.of();
            }
            return file.vectors();
        } catch (RuntimeException exception) {
            log.warn("Could not read embedding cache {}; embedding everything again", path, exception);
            return Map.of();
        }
    }

    /** 이번 색인에 쓰인 항목만 남겨 저장한다. 실패해도 검색에는 영향이 없다. */
    private void save(Map<String, float[]> vectors) {
        if (path == null) {
            return;
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), new CacheFile(modelId(), vectors));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not save embedding cache {}", path, exception);
        }
    }
}
