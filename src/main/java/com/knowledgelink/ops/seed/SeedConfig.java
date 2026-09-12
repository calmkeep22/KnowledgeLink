package com.knowledgelink.ops.seed;

import com.knowledgelink.account.application.AccountProvisioningService;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("seed")
@EnableConfigurationProperties(SeedProperties.class)
public class SeedConfig {

    @Bean
    SeedRunner seedRunner(SeedProperties seed,
                          WorkspaceRepository workspaceRepository,
                          AccountProvisioningService provisioningService,
                          Environment environment) {
        return new SeedRunner(seed, workspaceRepository, provisioningService, environment);
    }
}
