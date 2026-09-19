package com.knowledgelink.source.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgelink.job.application.JobHandler;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class JiraSyncConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JiraSyncConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JiraSyncStore.class, () -> new JiraSyncStore(new JdbcTemplate(), Clock.systemUTC()));

    @Test
    void 기본값은_꺼져_있어_실제_Jira를_부르지_않는다() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(JiraIssueSource.class);
            assertThat(context).doesNotHaveBean(JobHandler.class);
        });
    }

    @Test
    void 켜면_adapter와_SYNC_handler를_함께_등록한다() {
        runner.withPropertyValues("kl.jira.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(JiraCloudIssueSource.class);
            assertThat(context).getBean(JobHandler.class).isInstanceOf(JiraSyncHandler.class);
            assertThat(context.getBean(JiraProperties.class).pageSize()).isEqualTo(100);
        });
    }

    @Test
    void 페이지_크기가_API_상한을_넘으면_기동하지_않는다() {
        runner.withPropertyValues("kl.jira.enabled=true", "kl.jira.page-size=500")
                .run(context -> assertThat(context).hasFailed());
    }
}
