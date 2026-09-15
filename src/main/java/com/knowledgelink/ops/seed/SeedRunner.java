package com.knowledgelink.ops.seed;

import com.knowledgelink.access.application.GrantProvisioningService;
import com.knowledgelink.account.application.AccountProvisioningService;
import com.knowledgelink.source.application.SourceProvisioningService;
import com.knowledgelink.source.application.SourceProvisioningService.ScopeSpec;
import com.knowledgelink.workspace.domain.Workspace;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

/**
 * seed 프로필에서만 실행되는 등록기. 조직 → 계정 → 연결·scope → grant 순서로 등록한다.
 * 여러 번 실행해도 같은 결과이며(있는 것은 건너뜀), 비밀번호·토큰 값은 로그에 남기지 않는다.
 */
@Slf4j
public class SeedRunner implements ApplicationRunner {

    private final SeedProperties seed;
    private final WorkspaceRepository workspaceRepository;
    private final AccountProvisioningService accountProvisioning;
    private final SourceProvisioningService sourceProvisioning;
    private final GrantProvisioningService grantProvisioning;
    private final Environment environment;

    public SeedRunner(SeedProperties seed,
                      WorkspaceRepository workspaceRepository,
                      AccountProvisioningService accountProvisioning,
                      SourceProvisioningService sourceProvisioning,
                      GrantProvisioningService grantProvisioning,
                      Environment environment) {
        this.seed = seed;
        this.workspaceRepository = workspaceRepository;
        this.accountProvisioning = accountProvisioning;
        this.sourceProvisioning = sourceProvisioning;
        this.grantProvisioning = grantProvisioning;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        Workspace workspace = workspaceRepository.findByName(seed.workspaceName().strip())
                .orElseGet(() -> workspaceRepository.save(Workspace.create(seed.workspaceName())));
        seedAccounts(workspace);
        seedConnections(workspace);
        seedGrants(workspace);
    }

    private void seedAccounts(Workspace workspace) {
        for (SeedProperties.AccountSeed account : seed.accounts()) {
            String initialPassword = environment.getProperty(account.initialPasswordEnv());
            if (initialPassword == null) {
                throw new IllegalStateException(
                        "환경 변수 " + account.initialPasswordEnv() + "가 없습니다. 계정: " + account.loginId());
            }
            AccountProvisioningService.Result result = accountProvisioning.provisionIfAbsent(
                    workspace.getId(), account.loginId(), account.role(), initialPassword);
            log.info("seed account loginId={} role={} result={}", account.loginId(), account.role(), result);
        }
    }

    private void seedConnections(Workspace workspace) {
        for (SeedProperties.ConnectionSeed connection : seed.connections()) {
            SourceProvisioningService.Result result = sourceProvisioning.provisionIfAbsent(
                    workspace.getId(), connection.name(), connection.kind(), connection.siteId(),
                    connection.baseUrl(), connection.credentialRef(),
                    connection.scopes().stream()
                            .map(scope -> new ScopeSpec(scope.externalId(), scope.displayKey()))
                            .toList());
            log.info("seed connection name={} kind={} created={} scopesCreated={}",
                    connection.name(), connection.kind(), result.connectionCreated(), result.scopesCreated());
            if (environment.getProperty(connection.credentialRef()) == null) {
                log.warn("연결 {}의 인증 정보 환경 변수 {}가 아직 없습니다. 동기화 전에 설정하세요.",
                        connection.name(), connection.credentialRef());
            }
        }
    }

    private void seedGrants(Workspace workspace) {
        for (SeedProperties.GrantSeed grant : seed.grants()) {
            for (String rawRef : grant.scopes()) {
                ScopeRef ref = ScopeRef.parse(rawRef);
                GrantProvisioningService.Result result = grantProvisioning.grantIfAbsent(
                        workspace.getId(), grant.loginId(), ref.connectionName(), ref.displayKey());
                log.info("seed grant loginId={} scope={} result={}", grant.loginId(), rawRef, result);
            }
        }
    }

    /** "연결이름:표시키". GitHub 표시 키(owner/repo)에는 콜론이 없으므로 첫 콜론으로 나눈다. */
    record ScopeRef(String connectionName, String displayKey) {

        static ScopeRef parse(String raw) {
            int separator = raw == null ? -1 : raw.indexOf(':');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("grant scope는 '연결이름:표시키' 형식이어야 합니다: " + raw);
            }
            return new ScopeRef(raw.substring(0, separator).strip(), raw.substring(separator + 1).strip());
        }
    }
}
