package com.knowledgelink.job.domain;

/**
 * 작업 상태(명세 03 4장). QUEUED → RUNNING → SUCCEEDED가 기본 흐름이고, 일시 실패·lease 만료는 RETRY_WAIT를 거쳐
 * 다시 QUEUED로, 영구 실패나 시도 소진은 FAILED로 간다. SUCCEEDED는 다시 선점하지 않는다.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
