package com.knowledgelink.job.application;

import com.knowledgelink.job.domain.JobKind;
import java.util.Set;

/**
 * 작업 종류별 실행기. 외부 호출 중에는 DB transaction을 잡지 않는다.
 * 결과·커서처럼 소유권이 있을 때만 저장해야 하는 쓰기는 {@link JobContext#inLease(Runnable)}나
 * {@link JobOutcome#succeeded(Runnable)}로 한다. 소유권을 잃었으면 그 쓰기는 롤백된다.
 */
public interface JobHandler {

    Set<JobKind> kinds();

    JobOutcome execute(JobContext context);
}
