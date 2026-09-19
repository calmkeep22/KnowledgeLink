package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.WorkSummary;

/** AI 공급자 SDK와 HTTP 세부사항을 application 계층 밖으로 격리하는 출력 port. */
public interface ActivitySummaryGenerator {
    WorkSummary generate(SummaryGenerationRequest request);
}
