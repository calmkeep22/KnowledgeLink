package com.knowledgelink.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 애플리케이션에서 UUIDv7을 미리 발급하는 엔티티의 공통 부모.
 *
 * <p>ID를 직접 할당하면 Spring Data의 {@code save()}가 merge로 판단해 불필요한 SELECT를 한다.
 * {@link Persistable#isNew()}를 영속화 여부로 판단해 INSERT 한 번으로 저장한다.
 *
 * <p>ID는 새 엔티티를 만들 때만 발급한다. 하위 클래스의 생성 경로는 {@code super(UuidV7.next())}를 호출하고,
 * JPA가 조회 결과를 채울 때 쓰는 기본 생성자는 ID 생성기(전역 락)를 거치지 않는다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    /** JPA 전용. 조회 시 DB 값이 채워지고 {@link #markPersisted()}가 호출된다. */
    protected BaseEntity() {
    }

    /** 새 엔티티 생성용. */
    protected BaseEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.newEntity = false;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity that)) {
            return false;
        }
        return Hibernate.getClass(this) == Hibernate.getClass(that) && id.equals(that.getId());
    }

    @Override
    public final int hashCode() {
        return id.hashCode();
    }
}
