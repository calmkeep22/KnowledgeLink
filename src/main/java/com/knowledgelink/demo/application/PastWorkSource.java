package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.DemoActivity;
import java.util.List;

/** 유사 업무 검색 대상인 해결된 과거 업무. mock과 향후 실제 색인 자료를 교체하기 위한 입력 port. */
public interface PastWorkSource {
    List<DemoActivity> findAll();
}
