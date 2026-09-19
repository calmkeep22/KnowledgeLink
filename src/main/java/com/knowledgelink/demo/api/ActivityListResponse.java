package com.knowledgelink.demo.api;

import com.knowledgelink.demo.domain.DemoActivity;
import java.util.List;

public record ActivityListResponse(List<DemoActivity> activities) {
    public ActivityListResponse {
        activities = activities == null ? List.of() : List.copyOf(activities);
    }
}
