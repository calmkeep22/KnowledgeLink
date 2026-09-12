package com.knowledgelink.support;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.workspace.domain.Workspace;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
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
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final MutableClock clock;

    public TestFixtures(JdbcTemplate jdbcTemplate,
                        WorkspaceRepository workspaceRepository,
                        AccountRepository accountRepository,
                        PasswordEncoder passwordEncoder,
                        TransactionTemplate transactionTemplate,
                        MutableClock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRepository = workspaceRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public void reset() {
        clock.reset();
        jdbcTemplate.execute("TRUNCATE spring_session_attributes, spring_session, account, workspace CASCADE");
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
}
