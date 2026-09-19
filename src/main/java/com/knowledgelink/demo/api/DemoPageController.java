package com.knowledgelink.demo.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 발표자가 기억하기 쉬운 /demo/ 주소를 정적 한 페이지로 연결한다. */
@Controller
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class DemoPageController {

    @GetMapping({"/demo", "/demo/"})
    public String page() {
        return "forward:/demo/index.html";
    }
}
