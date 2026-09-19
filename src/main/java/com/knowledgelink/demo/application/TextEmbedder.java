package com.knowledgelink.demo.application;

import java.util.List;

/** 텍스트를 벡터로 바꾸는 출력 port. 공급자 실패는 {@link ActivitySummaryGenerationException}으로 알린다. */
public interface TextEmbedder {
    float[] embed(String text);

    String modelId();

    /**
     * 과거 업무 색인처럼 여러 텍스트를 한 번에 임베딩한다. 입력 순서대로 돌려준다.
     * 저장본 재사용·병렬 호출은 구현이 선택한다. 사용자 질의는 이 메서드를 쓰지 않는다.
     */
    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
