package com.knowledgelink.access.application;

import com.knowledgelink.access.domain.ScopeGrant;
import com.knowledgelink.access.domain.ScopeGrantRepository;
import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.account.domain.LoginIds;
import com.knowledgelink.source.domain.SourceConnection;
import com.knowledgelink.source.domain.SourceConnectionRepository;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.source.domain.SourceScopeRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 스크립트(seed)용 grant 등록. 이미 있으면 건너뛰고, 제거는 하지 않는다. */
@Service
@RequiredArgsConstructor
public class GrantProvisioningService {

    public enum Result {
        CREATED,
        ALREADY_EXISTS
    }

    private final AccountRepository accountRepository;
    private final SourceConnectionRepository connectionRepository;
    private final SourceScopeRepository scopeRepository;
    private final ScopeGrantRepository grantRepository;

    @Transactional
    public Result grantIfAbsent(UUID workspaceId, String rawLoginId, String connectionName, String displayKey) {
        Account account = LoginIds.normalize(rawLoginId)
                .flatMap(accountRepository::findByLoginId)
                .filter(found -> found.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("이 조직에 없는 계정입니다: " + rawLoginId));
        if (account.getRole() == AccountRole.ADMIN) {
            throw new IllegalArgumentException("ADMIN은 조직의 모든 scope를 보므로 grant가 필요 없습니다: " + rawLoginId);
        }
        SourceConnection connection = connectionRepository.findByWorkspaceIdAndName(workspaceId, connectionName)
                .orElseThrow(() -> new IllegalArgumentException("이 조직에 없는 연결입니다: " + connectionName));
        List<SourceScope> scopes = scopeRepository.findByConnectionIdAndDisplayKey(connection.getId(), displayKey);
        if (scopes.size() != 1) {
            throw new IllegalArgumentException(
                    "scope를 하나로 특정할 수 없습니다: " + connectionName + ":" + displayKey + " (" + scopes.size() + "개)");
        }
        SourceScope scope = scopes.getFirst();
        if (grantRepository.existsByAccountIdAndScopeId(account.getId(), scope.getId())) {
            return Result.ALREADY_EXISTS;
        }
        grantRepository.save(ScopeGrant.of(scope, account));
        return Result.CREATED;
    }
}
