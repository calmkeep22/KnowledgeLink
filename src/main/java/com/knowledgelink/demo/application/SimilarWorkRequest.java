package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.SimilarWorkMatch;
import java.util.List;

public record SimilarWorkRequest(String query, List<SimilarWorkMatch> matches) {
    public SimilarWorkRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("검색할 업무 설명이 필요합니다.");
        }
        matches = matches == null ? List.of() : List.copyOf(matches);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("설명할 검색 결과가 없습니다.");
        }
    }
}
