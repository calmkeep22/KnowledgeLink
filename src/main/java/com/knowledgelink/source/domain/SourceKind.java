package com.knowledgelink.source.domain;

public enum SourceKind {

    JIRA_CLOUD(ScopeKind.JIRA_PROJECT),
    GITHUB(ScopeKind.GITHUB_REPOSITORY);

    private final ScopeKind scopeKind;

    SourceKind(ScopeKind scopeKind) {
        this.scopeKind = scopeKind;
    }

    /** 이 연결에 속한 scope의 종류. */
    public ScopeKind scopeKind() {
        return scopeKind;
    }
}
