package com.knowledgelink.source.domain;

public enum ConnectionStatus {
    /** 정상. 동기화할 수 있다. */
    ACTIVE,
    /** 인증·설정 오류. 자동 재시도하지 않고 관리자 확인을 기다린다. */
    ERROR,
    /** 관리자가 끈 연결. */
    DISABLED
}
