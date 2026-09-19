package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.SimilarWorkExplanation;

/** 검색된 과거 업무만 근거로 새 업무와의 관련성을 설명하는 출력 port. */
public interface SimilarWorkExplainer {
    SimilarWorkExplanation explain(SimilarWorkRequest request);
}
