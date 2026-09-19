package com.knowledgelink.demo.application;

/**
 * 사용자의 업무 설명을 과거 업무 자료의 언어·용어에 맞춘 검색어로 바꾸는 출력 port.
 * 자료가 영어인데 질문이 한국어일 때 임베딩 검색이 약해지는 것을 보완한다.
 * 실패는 {@link ActivitySummaryGenerationException}으로 알리며, 호출하는 쪽은 원문으로 검색을 이어 간다.
 */
@FunctionalInterface
public interface QueryRewriter {
    String rewrite(String query);

    /** 확장하지 않고 원문을 그대로 쓴다. 네트워크 없는 fake 모드와 테스트용이다. */
    QueryRewriter NONE = query -> query;
}
