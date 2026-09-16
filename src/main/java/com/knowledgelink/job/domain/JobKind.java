package com.knowledgelink.job.domain;

/** 작업 종류와 그 종류가 쓰는 실행 슬롯. */
public enum JobKind {

    SYNC(SlotKey.SYNC),
    RECONCILE(SlotKey.SYNC),
    INDEX(SlotKey.INDEX),
    ANALYSIS(SlotKey.AI),
    QUESTION(SlotKey.AI),
    CARD(SlotKey.AI),
    LINK_SUGGEST(SlotKey.AI);

    private final SlotKey slot;

    JobKind(SlotKey slot) {
        this.slot = slot;
    }

    public SlotKey slot() {
        return slot;
    }

    /** 동기화·정합성 점검은 scope 하나를 대상으로 한다. */
    public boolean requiresScope() {
        return this == SYNC || this == RECONCILE;
    }
}
