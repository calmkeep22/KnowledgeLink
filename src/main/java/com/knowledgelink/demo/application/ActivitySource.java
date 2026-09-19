package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.DemoActivity;
import java.util.List;

/** mock와 향후 실제 Jira/GitHub 수집기를 교체하기 위한 입력 port. */
public interface ActivitySource {
    List<DemoActivity> findAll();
}
