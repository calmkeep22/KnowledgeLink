package com.knowledgelink.source.domain;

import java.util.regex.Pattern;

/** 접근 단위. 사용자는 grant가 있는 scope의 자료만 볼 수 있다. */
public enum ScopeKind {

    /** Jira 프로젝트. 표시 키는 프로젝트 키(PAY)이며 이슈 키 "PAY-142"의 앞부분이다. */
    JIRA_PROJECT(Pattern.compile("^[A-Z][A-Z0-9_]{1,19}$")),

    /** GitHub 저장소. 표시 키는 owner/repo. */
    GITHUB_REPOSITORY(Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}$"));

    private final Pattern displayKeyPattern;

    ScopeKind(Pattern displayKeyPattern) {
        this.displayKeyPattern = displayKeyPattern;
    }

    public boolean isValidDisplayKey(String displayKey) {
        return displayKey != null && displayKeyPattern.matcher(displayKey).matches();
    }
}
