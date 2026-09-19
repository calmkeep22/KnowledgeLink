package com.knowledgelink.source.jira;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Jira Cloud 수집 설정. 인증 정보는 여기 두지 않는다. 연결의 credential_ref가 가리키는 환경 변수에
 * {@code 서비스계정이메일:API토큰} 형식으로 둔다.
 *
 * <p>enabled=false면 SYNC handler를 등록하지 않는다. 로컬·CI에서 실제 Jira를 부르지 않게 하기 위해서다.
 */
@Validated
@ConfigurationProperties("kl.jira")
public record JiraProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("100") @Min(1) @Max(100) int pageSize) {

    public JiraProperties {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("kl.jira.timeout은 0보다 커야 합니다.");
        }
    }
}
