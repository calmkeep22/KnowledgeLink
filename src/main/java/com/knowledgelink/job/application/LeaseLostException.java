package com.knowledgelink.job.application;

import java.util.UUID;

/**
 * 이 실행기가 더 이상 작업의 소유자가 아니다(lease 만료, 또는 복구 후 다른 실행기가 재선점).
 * 이 예외가 난 transaction의 쓰기는 모두 롤백된다.
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(UUID jobId) {
        super("작업 " + jobId + "의 소유권을 잃었습니다.");
    }
}
