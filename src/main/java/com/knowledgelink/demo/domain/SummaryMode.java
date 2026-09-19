package com.knowledgelink.demo.domain;

public enum SummaryMode {
    MEMBER,
    PROJECT,
    HANDOFF;

    /** 화면 제목은 서버가 정한다. 외부 AI에는 팀원 이름을 보내지 않으므로 AI가 만든 제목에는 ID만 남는다. */
    public String titleFor(String subjectName) {
        return switch (this) {
            case MEMBER -> subjectName + "의 이번 주 업무 요약";
            case PROJECT -> subjectName + " 프로젝트 현황 요약";
            case HANDOFF -> subjectName + " 인수인계 요약";
        };
    }
}
