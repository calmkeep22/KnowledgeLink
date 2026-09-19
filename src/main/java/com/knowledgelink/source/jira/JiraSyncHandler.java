package com.knowledgelink.source.jira;

import com.knowledgelink.job.application.JobContext;
import com.knowledgelink.job.application.JobHandler;
import com.knowledgelink.job.application.JobOutcome;
import com.knowledgelink.job.domain.JobKind;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Jira 이슈·댓글을 페이지 단위로 저장하는 SYNC handler. 외부 호출 중에는 DB transaction을 잡지 않는다. */
public class JiraSyncHandler implements JobHandler {

    public static final String RATE_LIMITED = "JIRA_RATE_LIMITED";
    public static final String TEMPORARY_ERROR = "JIRA_TEMPORARY_ERROR";
    public static final String AUTHENTICATION_ERROR = "JIRA_AUTHENTICATION_ERROR";
    public static final String CONFIGURATION_ERROR = "JIRA_CONFIGURATION_ERROR";
    public static final String TARGET_UNAVAILABLE = "SYNC_TARGET_UNAVAILABLE";

    private final JiraIssueSource source;
    private final JiraSyncStore store;

    public JiraSyncHandler(JiraIssueSource source, JiraSyncStore store) {
        this.source = source;
        this.store = store;
    }

    @Override
    public Set<JobKind> kinds() {
        return Set.of(JobKind.SYNC);
    }

    @Override
    public JobOutcome execute(JobContext context) {
        if (context.lease().scopeId() == null) {
            return JobOutcome.failed(TARGET_UNAVAILABLE);
        }
        JiraSyncTarget target = store.findActiveTarget(context.lease().scopeId()).orElse(null);
        if (target == null) {
            return JobOutcome.failed(TARGET_UNAVAILABLE);
        }

        context.advanceStage("JIRA_SYNC");
        Instant updatedFrom = target.cursor() == null ? Instant.EPOCH : target.cursor().queryFrom();
        JiraCursor highWater = target.cursor();
        String pageToken = null;
        Set<String> seenTokens = new HashSet<>();
        try {
            do {
                if (context.leaseLost()) {
                    throw new IllegalStateException("Jira 외부 호출 전에 job lease를 잃었습니다.");
                }
                JiraPage page = source.fetch(target, updatedFrom, pageToken);
                JiraCursor nextHighWater = highWater;
                for (JiraIssue issue : page.issues()) {
                    nextHighWater = nextHighWater == null ? issue.cursor() : nextHighWater.max(issue.cursor());
                }
                if (!page.issues().isEmpty()) {
                    JiraCursor savedCursor = nextHighWater;
                    context.inLease(() -> store.savePage(target, page.issues(), savedCursor));
                }
                highWater = nextHighWater;
                if (page.last()) {
                    break;
                }
                pageToken = page.nextPageToken();
                if (!seenTokens.add(pageToken)) {
                    throw new JiraSourceException(JiraSourceException.Kind.CONFIGURATION, null);
                }
            } while (true);
            return JobOutcome.succeeded(() -> store.complete(target));
        } catch (JiraSourceException e) {
            return handleSourceError(context, target, e);
        }
    }

    private JobOutcome handleSourceError(JobContext context, JiraSyncTarget target, JiraSourceException error) {
        return switch (error.kind()) {
            case RATE_LIMIT -> JobOutcome.retryLater(RATE_LIMITED, error.retryAfter());
            case TEMPORARY -> JobOutcome.retryLater(TEMPORARY_ERROR, error.retryAfter());
            case AUTHENTICATION -> {
                context.inLease(() -> store.markConnectionError(target.connectionId(), AUTHENTICATION_ERROR));
                yield JobOutcome.failed(AUTHENTICATION_ERROR);
            }
            case CONFIGURATION -> {
                context.inLease(() -> store.markConnectionError(target.connectionId(), CONFIGURATION_ERROR));
                yield JobOutcome.failed(CONFIGURATION_ERROR);
            }
        };
    }
}
