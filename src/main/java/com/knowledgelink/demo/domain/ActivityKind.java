package com.knowledgelink.demo.domain;

/** 공개 데모에서 사용하는 가명 활동 유형. 실제 원본 연동 모델과 분리한다. */
public enum ActivityKind {
    JIRA_ISSUE,
    GITHUB_PULL_REQUEST,
    GITHUB_COMMIT
}
