package com.knowledgelink.demo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DemoPageControllerTest {

    @Test
    void demoPathForwardsToStaticPage() {
        assertEquals("forward:/demo/index.html", new DemoPageController().page());
    }
}
