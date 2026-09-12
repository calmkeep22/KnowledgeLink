package com.knowledgelink.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.SqlCapture;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ADR 0003 검증: ID를 미리 발급하는 엔티티가 {@code Persistable} 덕분에 SELECT 없이 저장되는지,
 * 그렇지 않을 때(Spring Data가 "ID가 있으니 기존 행"으로 판단) merge가 SELECT를 추가하는지 실제 SQL로 비교한다.
 */
@IntegrationTest
class PersistableSaveQueryIntegrationTest {

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
    }

    @Test
    void 새_엔티티는_SELECT_없이_INSERT_한_번으로_저장된다() {
        List<String> sql = SqlCapture.during(() -> workspaceRepository.save(Workspace.create("Persist Org")));

        assertThat(statements(sql, "select")).isEmpty();
        assertThat(statements(sql, "insert")).hasSize(1);
    }

    @Test
    void 기존_행으로_판단되면_merge가_SELECT를_먼저_실행한다() {
        Workspace workspace = judgedAsExisting(Workspace.create("Merge Org"));

        List<String> sql = SqlCapture.during(() -> workspaceRepository.save(workspace));

        assertThat(statements(sql, "select")).hasSize(1);
        assertThat(statements(sql, "insert")).hasSize(1);
    }

    @Test
    void 백_건을_저장하면_merge_경로의_쿼리가_두_배다() {
        List<Workspace> fresh = workspaces("fresh", 100);
        List<Workspace> judgedExisting = workspaces("merged", 100).stream()
                .map(PersistableSaveQueryIntegrationTest::judgedAsExisting)
                .toList();

        List<String> persisted = SqlCapture.during(() -> workspaceRepository.saveAll(fresh));
        List<String> merged = SqlCapture.during(() -> workspaceRepository.saveAll(judgedExisting));

        assertThat(statements(persisted, "select")).isEmpty();
        assertThat(statements(persisted, "insert")).hasSize(100);
        assertThat(statements(merged, "select")).hasSize(100);
        assertThat(statements(merged, "insert")).hasSize(100);
    }

    /** Persistable이 없을 때 Spring Data가 내리는 판단(ID가 이미 있으니 기존 행)을 재현한다. */
    private static Workspace judgedAsExisting(Workspace workspace) {
        ReflectionTestUtils.setField(workspace, "newEntity", false);
        return workspace;
    }

    private static List<Workspace> workspaces(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Workspace.create(prefix + "-" + i))
                .toList();
    }

    private static List<String> statements(List<String> sql, String keyword) {
        return sql.stream()
                .filter(statement -> statement.stripLeading().toLowerCase(Locale.ROOT).startsWith(keyword))
                .toList();
    }
}
