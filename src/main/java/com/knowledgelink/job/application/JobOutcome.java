package com.knowledgelink.job.application;

import java.time.Duration;
import java.util.Objects;

/** handler의 실행 결과. 상태 전환과 슬롯 반납은 실행기가 엔진을 통해 한 transaction에서 처리한다. */
public sealed interface JobOutcome {

    /** resultWriter는 SUCCEEDED 전환과 같은 transaction에서 실행된다(결과와 상태를 함께 저장). */
    record Succeeded(Runnable resultWriter) implements JobOutcome {

        public Succeeded {
            Objects.requireNonNull(resultWriter, "resultWriter");
        }
    }

    /** 일시 실패·요청 제한. notBefore는 Retry-After 같은 최소 대기 시간이다. 시도를 다 썼으면 FAILED가 된다. */
    record RetryLater(String errorCode, Duration notBefore) implements JobOutcome {

        public RetryLater {
            Objects.requireNonNull(errorCode, "errorCode");
            notBefore = notBefore == null ? Duration.ZERO : notBefore;
        }
    }

    /** 인증·설정·입력 오류처럼 다시 해도 소용없는 실패. 시도가 남아도 바로 FAILED다. */
    record Failed(String errorCode) implements JobOutcome {

        public Failed {
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }

    static JobOutcome succeeded() {
        return new Succeeded(() -> { });
    }

    static JobOutcome succeeded(Runnable resultWriter) {
        return new Succeeded(resultWriter);
    }

    static JobOutcome retryLater(String errorCode) {
        return new RetryLater(errorCode, Duration.ZERO);
    }

    static JobOutcome retryLater(String errorCode, Duration notBefore) {
        return new RetryLater(errorCode, notBefore);
    }

    static JobOutcome failed(String errorCode) {
        return new Failed(errorCode);
    }
}
