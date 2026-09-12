package com.knowledgelink.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.MutableClock;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PersistenceConfig 검증: {@code @EnableJpaAuditing}이 주입된 {@code Clock}의 시각을 DB에 기록하는지,
 * 엔티티가 아니라 JDBC로 DB 값을 직접 읽어 확인한다.
 *
 * <p>고정 시각은 마이크로초 단위로 둔다. PostgreSQL timestamptz의 정밀도가 마이크로초라서다.
 */
@IntegrationTest
class AuditingClockIntegrationTest {

    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456Z");

    @Autowired
    MutableClock clock;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
    }

    @Test
    void 저장할_때_주입된_Clock의_시각이_created_at에_기록된다() {
        clock.set(CREATED);

        Workspace workspace = workspaceRepository.save(Workspace.create("Clock Org"));

        assertThat(storedCreatedAt("workspace", workspace.getId())).isEqualTo(CREATED);
    }

    @Test
    void 수정해도_created_at은_처음_값을_유지한다() {
        clock.set(CREATED);
        Workspace workspace = fixtures.workspace("Update Org");
        Account account = fixtures.readyAccount(workspace, "dev.kim", AccountRole.MEMBER, "ready-password-123");

        clock.advance(Duration.ofDays(1));
        transactionTemplate.executeWithoutResult(status ->
                accountRepository.findById(account.getId()).orElseThrow().changePassword("{noop}changed"));

        Integer credentialVersion = jdbcTemplate.queryForObject(
                "SELECT credential_version FROM account WHERE id = ?", Integer.class, account.getId());
        assertThat(credentialVersion).as("UPDATE가 실제로 실행됐는지").isEqualTo(2);
        assertThat(storedCreatedAt("account", account.getId())).isEqualTo(CREATED);
    }

    @Test
    void JVM_기본_시간대가_서울이어도_같은_순간으로_저장된다() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            clock.set(CREATED);

            Workspace workspace = workspaceRepository.save(Workspace.create("Seoul Org"));

            assertThat(storedCreatedAt("workspace", workspace.getId())).isEqualTo(CREATED);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private Instant storedCreatedAt(String table, UUID id) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT created_at FROM " + table + " WHERE id = ?", OffsetDateTime.class, id);
        return value.toInstant();
    }
}
