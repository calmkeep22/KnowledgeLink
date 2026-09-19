package com.knowledgelink.demo.application;

/** 텍스트를 벡터로 바꾸는 출력 port. 공급자 실패는 {@link ActivitySummaryGenerationException}으로 알린다. */
public interface TextEmbedder {
    float[] embed(String text);

    String modelId();
}
