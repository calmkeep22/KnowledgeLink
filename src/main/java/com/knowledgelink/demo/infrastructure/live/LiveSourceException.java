package com.knowledgelink.demo.infrastructure.live;

/** 공개 Jira·GitHub 수집 실패. 저장본이나 예시 데이터로 대체할 수 있는 실패다. */
final class LiveSourceException extends RuntimeException {
    LiveSourceException(String message) {
        super(message);
    }

    LiveSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
