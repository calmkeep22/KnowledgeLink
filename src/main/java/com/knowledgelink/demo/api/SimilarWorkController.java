package com.knowledgelink.demo.api;

import com.knowledgelink.demo.application.SimilarWorkService;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import com.knowledgelink.demo.domain.SimilarWorkResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 새 업무 설명을 본문으로 받는다. 자유 입력을 URL·접근 로그에 남기지 않기 위해 POST를 쓴다. */
@RestController
@RequestMapping("/api/v1/demo")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class SimilarWorkController {

    private final SimilarWorkService similarWorkService;

    @PostMapping("/similar-work")
    public SimilarWorkResult search(@Valid @RequestBody SimilarWorkQuery body) {
        return similarWorkService.search(body.query());
    }

    @GetMapping("/past-work/info")
    public PastWorkSourceInfo pastWorkInfo() {
        return similarWorkService.sourceInfo();
    }
}
