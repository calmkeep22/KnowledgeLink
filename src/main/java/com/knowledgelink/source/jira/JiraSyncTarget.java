package com.knowledgelink.source.jira;

import java.util.UUID;

/** 외부 Jira 호출에 필요한 연결·프로젝트 정보. credentialRef는 비밀값 자체가 아니라 환경 변수 이름이다. */
public record JiraSyncTarget(UUID workspaceId, UUID connectionId, UUID scopeId, String projectId,
                             String projectKey, String baseUrl, String credentialRef, JiraCursor cursor) {
}
