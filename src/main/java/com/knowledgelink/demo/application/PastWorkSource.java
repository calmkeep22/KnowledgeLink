package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import java.util.List;
import java.util.Map;

/** 유사 업무 검색 대상인 해결된 과거 업무. mock과 실제 Jira·GitHub 수집 자료를 교체하기 위한 입력 port. */
public interface PastWorkSource {
    List<DemoActivity> findAll();

    /** 과거 업무 ID별로 서로 연결된 업무 ID(Jira 이슈 ↔ 이를 고친 PR). 연결 정보가 없으면 비어 있다. */
    default Map<String, List<String>> links() {
        return Map.of();
    }

    /** 출처 이름과 이 자료에 맞는 예시 질문. */
    default PastWorkSourceInfo info() {
        return PastWorkSourceInfo.of("과거 업무", "custom", null, findAll(), links(), List.of());
    }
}
