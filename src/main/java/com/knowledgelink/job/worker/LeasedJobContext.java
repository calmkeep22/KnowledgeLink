package com.knowledgelink.job.worker;

import com.knowledgelink.job.application.JobContext;
import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.persistence.JobEngine;
import java.util.concurrent.atomic.AtomicBoolean;

/** 선점한 lease 하나에 묶인 {@link JobContext}. 쓰기는 엔진이 소유권을 확인한 뒤 같은 transaction에서 실행한다. */
class LeasedJobContext implements JobContext {

    private final JobEngine engine;
    private final JobLease lease;
    private final AtomicBoolean leaseLost = new AtomicBoolean();

    LeasedJobContext(JobEngine engine, JobLease lease) {
        this.engine = engine;
        this.lease = lease;
    }

    @Override
    public JobLease lease() {
        return lease;
    }

    @Override
    public void inLease(Runnable writes) {
        engine.withLease(lease, writes);
    }

    @Override
    public void advanceStage(String stage) {
        engine.advanceStage(lease, stage);
    }

    @Override
    public boolean leaseLost() {
        return leaseLost.get();
    }

    void markLeaseLost() {
        leaseLost.set(true);
    }
}
