package com.knowledgelink.job.api.dto;

import java.util.UUID;

/** 작업을 접수했다는 응답(202). 수동 재시도는 새 작업을 만들지 않고 같은 jobId를 돌려준다. */
public record JobAcceptedResponse(UUID jobId) {
}
