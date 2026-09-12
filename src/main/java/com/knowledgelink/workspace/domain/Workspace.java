package com.knowledgelink.workspace.domain;

import com.knowledgelink.common.id.UuidV7;
import com.knowledgelink.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "workspace")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace extends BaseEntity {

    public static final int NAME_MAX_LENGTH = 100;

    @Column(name = "name", nullable = false)
    private String name;

    private Workspace(String name) {
        super(UuidV7.next());
        this.name = name;
    }

    public static Workspace create(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("workspace 이름은 1~" + NAME_MAX_LENGTH + "자여야 합니다.");
        }
        return new Workspace(normalized);
    }
}
