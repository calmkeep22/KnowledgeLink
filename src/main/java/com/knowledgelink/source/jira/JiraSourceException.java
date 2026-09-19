package com.knowledgelink.source.jira;

import java.time.Duration;

/** Jira 공급자 오류를 작업 엔진의 실패·재시도 정책으로 옮기기 위한 예외. */
public class JiraSourceException extends RuntimeException {

    public enum Kind { RATE_LIMIT, TEMPORARY, AUTHENTICATION, CONFIGURATION }

    private final Kind kind;
    private final Duration retryAfter;

    public JiraSourceException(Kind kind, Duration retryAfter, Throwable cause) {
        super(kind.name(), cause);
        this.kind = kind;
        this.retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
    }

    public JiraSourceException(Kind kind, Duration retryAfter) {
        this(kind, retryAfter, null);
    }

    public Kind kind() {
        return kind;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
