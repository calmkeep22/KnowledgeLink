package com.knowledgelink.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.knowledgelink.access.domain.ScopeGrantRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.source.domain.SourceScopeRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ScopeAccessPolicyTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID PAY = UUID.randomUUID();
    private static final UUID ORD = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final SourceScopeRepository scopeRepository = mock(SourceScopeRepository.class);
    private final ScopeGrantRepository grantRepository = mock(ScopeGrantRepository.class);
    private final ScopeAccessPolicy policy = new ScopeAccessPolicy(scopeRepository, grantRepository);

    private final AccountPrincipal admin = principal(AccountRole.ADMIN);
    private final AccountPrincipal member = principal(AccountRole.MEMBER);

    private static AccountPrincipal principal(AccountRole role) {
        return new AccountPrincipal(UUID.randomUUID(), WORKSPACE, role.name().toLowerCase(), role, false, 1);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void ADMIN은_조직의_켜진_scope_전부_MEMBER는_grant받은_것만_본다() {
        given(scopeRepository.findEnabledIdsByWorkspaceId(WORKSPACE)).willReturn(List.of(PAY, ORD));
        given(grantRepository.findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE)).willReturn(List.of(PAY));

        assertThat(policy.accessibleScopes(admin).ids()).containsExactlyInAnyOrder(PAY, ORD);
        assertThat(policy.accessibleScopes(member).ids()).containsExactly(PAY);
        verify(grantRepository, never()).findEnabledScopeIdsGrantedTo(admin.accountId(), WORKSPACE);
    }

    @Test
    void 볼_수_없는_scope는_403이_아니라_404다() {
        given(grantRepository.findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE)).willReturn(List.of(PAY));

        policy.requireAccessible(member, PAY);
        assertErrorCode(() -> policy.requireAccessible(member, ORD), ErrorCode.RESOURCE_NOT_FOUND);
        assertErrorCode(() -> policy.requireAccessible(member, null), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 범위를_고르지_않으면_볼_수_있는_전체다() {
        given(grantRepository.findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE)).willReturn(List.of(PAY, ORD));

        assertThat(policy.narrow(member, List.of()).ids()).containsExactlyInAnyOrder(PAY, ORD);
        assertThat(policy.narrow(member, List.of(ORD)).ids()).containsExactly(ORD);
    }

    @Test
    void 고른_범위에_볼_수_없는_scope가_섞이면_404다() {
        given(grantRepository.findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE)).willReturn(List.of(PAY));

        assertErrorCode(() -> policy.narrow(member, List.of(PAY, OTHER)), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 볼_수_있는_scope가_하나도_없으면_422다() {
        given(grantRepository.findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE)).willReturn(List.of());

        assertErrorCode(() -> policy.narrow(member, List.of()), ErrorCode.NO_ACCESSIBLE_SCOPE);
    }

    @Test
    void 같은_요청_안에서는_한_번만_조회하고_다음_요청에서는_다시_조회한다() {
        given(grantRepository.findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE)).willReturn(List.of(PAY));

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        policy.accessibleScopes(member);
        policy.requireAccessible(member, PAY);
        policy.narrow(member, List.of());
        verify(grantRepository, times(1)).findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE);

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        policy.accessibleScopes(member);
        verify(grantRepository, times(2)).findEnabledScopeIdsGrantedTo(member.accountId(), WORKSPACE);
    }

    private static void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }
}
