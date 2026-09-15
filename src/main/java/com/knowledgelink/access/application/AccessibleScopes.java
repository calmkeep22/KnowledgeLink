package com.knowledgelink.access.application;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 요청자가 볼 수 있는 scope ID 집합. 이후 모든 자료 조회는 이 집합을 SQL 조건으로 먼저 걸고 나서
 * 정렬·검색한다(권한 밖 자료가 결과에 섞이거나 개수로 드러나지 않게).
 */
public record AccessibleScopes(Set<UUID> ids) {

    public AccessibleScopes {
        ids = Set.copyOf(ids);
    }

    public static AccessibleScopes of(Collection<UUID> ids) {
        return new AccessibleScopes(Set.copyOf(ids));
    }

    public boolean contains(UUID scopeId) {
        return scopeId != null && ids.contains(scopeId);
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }
}
