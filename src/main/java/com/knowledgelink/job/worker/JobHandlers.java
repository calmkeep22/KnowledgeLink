package com.knowledgelink.job.worker;

import com.knowledgelink.job.application.JobHandler;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.SlotKey;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 작업 종류 → handler. 종류마다 handler는 하나다. handler가 없는 종류는 선점하지 않는다. */
public class JobHandlers {

    private final Map<JobKind, JobHandler> byKind = new EnumMap<>(JobKind.class);

    public JobHandlers(List<JobHandler> handlers) {
        for (JobHandler handler : handlers) {
            for (JobKind kind : handler.kinds()) {
                JobHandler existing = byKind.putIfAbsent(kind, handler);
                if (existing != null) {
                    throw new IllegalStateException("작업 종류 " + kind + "에 handler가 둘 있습니다: "
                            + existing.getClass().getName() + ", " + handler.getClass().getName());
                }
            }
        }
    }

    /** 이 슬롯에서 실행할 수 있는 종류. 비어 있으면 그 슬롯은 선점하지 않는다. */
    public Set<JobKind> kindsFor(SlotKey slot) {
        Set<JobKind> kinds = EnumSet.noneOf(JobKind.class);
        for (JobKind kind : byKind.keySet()) {
            if (kind.slot() == slot) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    public JobHandler handlerFor(JobKind kind) {
        JobHandler handler = byKind.get(kind);
        if (handler == null) {
            throw new IllegalStateException("작업 종류 " + kind + "의 handler가 없습니다.");
        }
        return handler;
    }
}
