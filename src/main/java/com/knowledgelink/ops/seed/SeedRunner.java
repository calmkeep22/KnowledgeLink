package com.knowledgelink.ops.seed;

import com.knowledgelink.account.application.AccountProvisioningService;
import com.knowledgelink.workspace.domain.Workspace;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

/**
 * seed 프로필에서만 실행되는 계정 등록기. 여러 번 실행해도 같은 결과다(이미 있는 계정은 건너뜀).
 * 비밀번호 값은 로그에 남기지 않는다.
 */
@Slf4j
public class SeedRunner implements ApplicationRunner {

    private final SeedProperties seed;
    private final WorkspaceRepository workspaceRepository;
    private final AccountProvisioningService provisioningService;
    private final Environment environment;

    public SeedRunner(SeedProperties seed,
                      WorkspaceRepository workspaceRepository,
                      AccountProvisioningService provisioningService,
                      Environment environment) {
        this.seed = seed;
        this.workspaceRepository = workspaceRepository;
        this.provisioningService = provisioningService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        Workspace workspace = workspaceRepository.findByName(seed.workspaceName().strip())
                .orElseGet(() -> workspaceRepository.save(Workspace.create(seed.workspaceName())));

        for (SeedProperties.AccountSeed account : seed.accounts()) {
            String initialPassword = environment.getProperty(account.initialPasswordEnv());
            if (initialPassword == null) {
                throw new IllegalStateException(
                        "환경 변수 " + account.initialPasswordEnv() + "가 없습니다. 계정: " + account.loginId());
            }
            AccountProvisioningService.Result result = provisioningService.provisionIfAbsent(
                    workspace.getId(), account.loginId(), account.role(), initialPassword);
            log.info("seed account loginId={} role={} result={}", account.loginId(), account.role(), result);
        }
    }
}
