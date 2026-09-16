package com.knowledgelink.support;

import com.knowledgelink.access.domain.ScopeGrant;
import com.knowledgelink.access.domain.ScopeGrantRepository;
import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import com.knowledgelink.source.domain.SourceConnection;
import com.knowledgelink.source.domain.SourceConnectionRepository;
import com.knowledgelink.source.domain.SourceKind;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.source.domain.SourceScopeRepository;
import com.knowledgelink.workspace.domain.Workspace;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** 통합 테스트용 데이터 준비·정리. 테스트마다 테이블과 시계를 초기화해 서로 영향을 주지 않게 한다. */
@TestComponent
public class TestFixtures {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceRepository workspaceRepository;
    private final AccountRepository accountRepository;
    private final SourceConnectionRepository connectionRepository;
    private final SourceScopeRepository scopeRepository;
    private final ScopeGrantRepository grantRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final MutableClock clock;

    public TestFixtures(JdbcTemplate jdbcTemplate,
                        WorkspaceRepository workspaceRepository,
                        AccountRepository accountRepository,
                        SourceConnectionRepository connectionRepository,
                        SourceScopeRepository scopeRepository,
                        ScopeGrantRepository grantRepository,
                        PasswordEncoder passwordEncoder,
                        TransactionTemplate transactionTemplate,
                        MutableClock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRepository = workspaceRepository;
        this.accountRepository = accountRepository;
        this.connectionRepository = connectionRepository;
        this.scopeRepository = scopeRepository;
        this.grantRepository = grantRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public void reset() {
        clock.reset();
        jdbcTemplate.execute("TRUNCATE job, scope_grant, source_scope, source_connection, "
                + "spring_session_attributes, spring_session, account, workspace CASCADE");
        // execution_slot은 job을 참조해 CASCADE로 함께 비워진다. 마이그레이션이 넣는 고정 3행을 다시 넣는다.
        jdbcTemplate.execute("INSERT INTO execution_slot (slot_key) VALUES ('SYNC'), ('INDEX'), ('AI') "
                + "ON CONFLICT DO NOTHING");
    }

    public Workspace workspace(String name) {
        return workspaceRepository.save(Workspace.create(name));
    }

    /** 초기 비밀번호 변경 전 상태의 계정. */
    public Account newAccount(Workspace workspace, String loginId, AccountRole role, String password) {
        return accountRepository.save(
                Account.provision(workspace.getId(), loginId, passwordEncoder.encode(password), role));
    }

    /** 초기 비밀번호를 이미 바꾼 계정. */
    public Account readyAccount(Workspace workspace, String loginId, AccountRole role, String password) {
        Account account = Account.provision(workspace.getId(), loginId, passwordEncoder.encode("unused-initial-pw"), role);
        account.changePassword(passwordEncoder.encode(password));
        return accountRepository.save(account);
    }

    public void deactivate(String loginId) {
        transactionTemplate.executeWithoutResult(
                status -> accountRepository.findByLoginId(loginId).orElseThrow().deactivate());
    }

    public SourceConnection jiraConnection(Workspace workspace, String name, String siteId) {
        return connectionRepository.save(SourceConnection.create(workspace.getId(), name, SourceKind.JIRA_CLOUD,
                siteId, "https://" + siteId + ".atlassian.net", "KL_JIRA_TOKEN"));
    }

    public SourceConnection githubConnection(Workspace workspace, String name) {
        return connectionRepository.save(SourceConnection.create(workspace.getId(), name, SourceKind.GITHUB,
                null, "https://api.github.com", "KL_GITHUB_TOKEN"));
    }

    public SourceScope scope(SourceConnection connection, String externalId, String displayKey) {
        return scopeRepository.save(SourceScope.create(connection, externalId, displayKey));
    }

    public void grant(SourceScope scope, Account account) {
        grantRepository.save(ScopeGrant.of(scope, account));
    }

    public void revoke(SourceScope scope, Account account) {
        jdbcTemplate.update("DELETE FROM scope_grant WHERE scope_id = ? AND account_id = ?",
                scope.getId(), account.getId());
    }

    public void disable(SourceScope scope) {
        transactionTemplate.executeWithoutResult(
                status -> scopeRepository.findById(scope.getId()).orElseThrow().disable());
    }

    public long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    public long countJobs(JobStatus status) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM job WHERE status = ?", Long.class,
                status.name());
        return count == null ? 0 : count;
    }

    /** 슬롯 한 행. 비어 있으면 job_id·run_token·lease_expires_at이 null이다. */
    public Map<String, Object> slot(SlotKey key) {
        return jdbcTemplate.queryForMap(
                "SELECT job_id, run_token, lease_expires_at FROM execution_slot WHERE slot_key = ?", key.name());
    }

    /** 작업 결과·커서 저장을 흉내 낸다. 호출한 transaction 안에서 실행된다. */
    public void markSynced(SourceScope scope, Instant at) {
        jdbcTemplate.update("UPDATE source_scope SET last_synced_at = ? WHERE id = ?",
                at.atOffset(ZoneOffset.UTC), scope.getId());
    }

    public Instant lastSyncedAt(SourceScope scope) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT last_synced_at FROM source_scope WHERE id = ?", OffsetDateTime.class, scope.getId());
        return value == null ? null : value.toInstant();
    }
}
