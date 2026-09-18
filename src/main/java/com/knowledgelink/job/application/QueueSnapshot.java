package com.knowledgelink.job.application;

import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import java.util.Map;
import java.util.Set;

/** 지표용 큐 상태. 상태별 작업 수(없는 상태는 빠진다)와 지금 사용 중인 슬롯. */
public record QueueSnapshot(Map<JobStatus, Long> jobsByStatus, Set<SlotKey> busySlots) {

    public QueueSnapshot {
        jobsByStatus = Map.copyOf(jobsByStatus);
        busySlots = Set.copyOf(busySlots);
    }
}
