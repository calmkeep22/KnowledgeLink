package com.knowledgelink.job.application;

/**
 * handler가 실행 중에 쓰는 도구. 소유권 검사가 필요한 쓰기는 모두 여기를 거친다. 구현은 실행기가 넘겨준다.
 */
public interface JobContext {

    JobLease lease();

    /** 결과·커서처럼 소유권이 있을 때만 저장해야 하는 쓰기. 소유권을 잃었으면 {@link LeaseLostException}으로 롤백된다. */
    void inLease(Runnable writes);

    /** 재시도할 때 이어서 시작할 단계를 기록한다. */
    void advanceStage(String stage);

    /** heartbeat가 소유권을 잃었으면 true. 긴 작업은 외부 호출 전에 확인하고 멈춘다. */
    boolean leaseLost();
}
