package com.knowledgelink.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.job.domain.JobKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NewJobTest {

    private static final UUID WORKSPACE = UUID.randomUUID();

    @Test
    void 동기화와_정합성_점검은_scope가_있어야_한다() {
        assertThatThrownBy(() -> NewJob.forTarget(WORKSPACE, JobKind.SYNC, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewJob.forTarget(WORKSPACE, JobKind.RECONCILE, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NewJob.forScope(WORKSPACE, JobKind.SYNC, UUID.randomUUID()).scopeId()).isNotNull();
    }

    @Test
    void 입력을_주지_않으면_빈_JSON_객체다() {
        assertThat(NewJob.forTarget(WORKSPACE, JobKind.ANALYSIS, UUID.randomUUID()).snapshotJson()).isEqualTo("{}");
        assertThat(new NewJob(WORKSPACE, JobKind.INDEX, null, null, null, " ").snapshotJson()).isEqualTo("{}");
    }
}
