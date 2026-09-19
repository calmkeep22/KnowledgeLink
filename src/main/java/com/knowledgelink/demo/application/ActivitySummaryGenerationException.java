package com.knowledgelink.demo.application;

/** 공급자 응답을 안전한 도메인 실패로 바꾼다. 원문 응답이나 비밀값은 메시지에 포함하지 않는다. */
public final class ActivitySummaryGenerationException extends RuntimeException {
    public ActivitySummaryGenerationException(String message) {
        super(message);
    }

    public ActivitySummaryGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
