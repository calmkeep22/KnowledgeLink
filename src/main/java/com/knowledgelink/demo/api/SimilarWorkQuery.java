package com.knowledgelink.demo.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 새 업무 설명. 길이 상한은 서비스의 정규화 규칙과 같다. */
public record SimilarWorkQuery(
        @NotBlank @Size(max = 500) String query
) {
}
