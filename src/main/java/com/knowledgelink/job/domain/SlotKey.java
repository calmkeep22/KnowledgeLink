package com.knowledgelink.job.domain;

/**
 * 서버 전체 실행 슬롯. 슬롯마다 한 번에 한 작업만 실행하며, 슬롯이 다르면 동시에 실행된다(INDEX 임베딩과 AI 작업).
 */
public enum SlotKey {

    /** SYNC, RECONCILE. */
    SYNC,

    /** INDEX. */
    INDEX,

    /** ANALYSIS, QUESTION, CARD, LINK_SUGGEST. */
    AI
}
