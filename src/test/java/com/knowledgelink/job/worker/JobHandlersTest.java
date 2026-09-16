package com.knowledgelink.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.job.application.JobContext;
import com.knowledgelink.job.application.JobHandler;
import com.knowledgelink.job.application.JobOutcome;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.SlotKey;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobHandlersTest {

    @Test
    void 작업_종류는_명세의_슬롯에서만_실행된다() {
        assertThat(EnumSet.allOf(JobKind.class).stream().filter(kind -> kind.slot() == SlotKey.SYNC))
                .containsExactlyInAnyOrder(JobKind.SYNC, JobKind.RECONCILE);
        assertThat(EnumSet.allOf(JobKind.class).stream().filter(kind -> kind.slot() == SlotKey.INDEX))
                .containsExactly(JobKind.INDEX);
        assertThat(EnumSet.allOf(JobKind.class).stream().filter(kind -> kind.slot() == SlotKey.AI))
                .containsExactlyInAnyOrder(JobKind.ANALYSIS, JobKind.QUESTION, JobKind.CARD, JobKind.LINK_SUGGEST);
    }

    @Test
    void handler가_있는_종류만_슬롯의_선점_대상이다() {
        JobHandlers handlers = new JobHandlers(List.of(handler(JobKind.SYNC), handler(JobKind.ANALYSIS, JobKind.CARD)));

        assertThat(handlers.kindsFor(SlotKey.SYNC)).containsExactly(JobKind.SYNC);
        assertThat(handlers.kindsFor(SlotKey.INDEX)).isEmpty();
        assertThat(handlers.kindsFor(SlotKey.AI)).containsExactlyInAnyOrder(JobKind.ANALYSIS, JobKind.CARD);
    }

    @Test
    void 한_종류에_handler가_둘이면_시작할_때_거절한다() {
        assertThatThrownBy(() -> new JobHandlers(List.of(handler(JobKind.SYNC), handler(JobKind.SYNC, JobKind.INDEX))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYNC");
    }

    @Test
    void handler가_없는_종류는_실행하지_않는다() {
        JobHandlers handlers = new JobHandlers(List.of());

        assertThat(handlers.kindsFor(SlotKey.AI)).isEmpty();
        assertThatThrownBy(() -> handlers.handlerFor(JobKind.ANALYSIS)).isInstanceOf(IllegalStateException.class);
    }

    private static JobHandler handler(JobKind... kinds) {
        Set<JobKind> handled = EnumSet.of(kinds[0], kinds);
        return new JobHandler() {
            @Override
            public Set<JobKind> kinds() {
                return handled;
            }

            @Override
            public JobOutcome execute(JobContext context) {
                return JobOutcome.succeeded();
            }
        };
    }
}
