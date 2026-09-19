package com.knowledgelink.source.jira;

import com.knowledgelink.job.application.JobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jira outbound adapter가 제공된 환경에서만 SYNC handler를 작업 엔진에 연결한다. */
@Configuration(proxyBeanMethods = false)
public class JiraSyncConfig {

    @Bean
    @ConditionalOnBean(JiraIssueSource.class)
    JobHandler jiraSyncJobHandler(JiraIssueSource source, JiraSyncStore store) {
        return new JiraSyncHandler(source, store);
    }
}
