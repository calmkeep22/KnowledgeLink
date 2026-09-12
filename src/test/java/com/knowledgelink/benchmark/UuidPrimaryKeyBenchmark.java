package com.knowledgelink.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgelink.common.id.UuidV7;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ADR 0003 벤치마크: 같은 테이블 구조에 PK만 UUIDv4 / UUIDv7로 바꿔 쓰기·인덱스·조회를 비교한다.
 *
 * <ul>
 *   <li>INSERT 시간과 WAL 생성량: 무작위 삽입(v4)의 페이지 분할·전체 페이지 기록 비용. JDBC 왕복 포함</li>
 *   <li>PK 인덱스 크기·리프 채움률·단편화(pgstattuple): 인덱스가 얼마나 촘촘하고 물리적으로 순서대로인지</li>
 *   <li>PK 조회: 서버 안에서 인덱스를 {@value #LOOKUPS}번 탐색한 실행 시간. 네트워크 왕복은 제외</li>
 * </ul>
 *
 * <p>Docker Desktop(Windows)의 쿼리 왕복은 약 10ms라 건별 조회를 클라이언트에서 재면 인덱스 비용(µs)이 가려진다.
 * 그래서 조회는 한 쿼리로 묶어 {@code EXPLAIN ANALYZE}의 서버 실행 시간을 쓴다.
 * 수치는 PC·Docker 자원에 따라 달라지므로 원리상 항상 성립하는 인덱스 구조 차이만 단언한다.
 * 실행: {@code ./gradlew benchmark}
 */
@Tag("benchmark")
class UuidPrimaryKeyBenchmark {

    private static final int ROWS = 1_000_000;
    private static final int BATCH_SIZE = 10_000;
    private static final int ROUNDS = 3;
    private static final int LOOKUPS = 50_000;
    private static final String PAYLOAD = "x".repeat(64);
    private static final Pattern EXECUTION_TIME = Pattern.compile("\"Execution Time\"\\s*:\\s*([0-9.]+)");
    private static final Path REPORT = Path.of("build", "reports", "benchmark", "uuid-primary-key.md");

    private static PostgreSQLContainer postgres;

    enum Variant {
        UUID_V4("bench_uuid_v4", UUID::randomUUID),
        UUID_V7("bench_uuid_v7", UuidV7::next);

        private final String table;
        private final Supplier<UUID> generator;

        Variant(String table, Supplier<UUID> generator) {
            this.table = table;
            this.generator = generator;
        }
    }

    record RoundResult(Variant variant, long insertMillis, long walBytes, double lookupMicros) {
    }

    record IndexStats(long indexBytes, long leafPages, double avgLeafDensity, double leafFragmentation) {
    }

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void UUIDv4와_UUIDv7_PK의_쓰기_인덱스_조회를_비교한다() throws Exception {
        try (Connection connection = connect()) {
            execute(connection, "CREATE EXTENSION IF NOT EXISTS pgstattuple");

            List<RoundResult> rounds = new ArrayList<>();
            Map<Variant, IndexStats> indexStats = new EnumMap<>(Variant.class);
            for (int round = 0; round < ROUNDS; round++) {
                // 먼저 실행한 쪽이 캐시·JIT에서 손해를 보지 않도록 순서를 번갈아 바꾼다.
                List<Variant> order = round % 2 == 0
                        ? List.of(Variant.UUID_V4, Variant.UUID_V7)
                        : List.of(Variant.UUID_V7, Variant.UUID_V4);
                for (Variant variant : order) {
                    rounds.add(runRound(connection, variant));
                    indexStats.put(variant, indexStats(connection, variant));
                }
            }

            String report = report(connection, rounds, indexStats);
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, report);
            System.out.println(report);

            IndexStats v4 = indexStats.get(Variant.UUID_V4);
            IndexStats v7 = indexStats.get(Variant.UUID_V7);
            assertThat(v7.leafFragmentation()).isLessThan(v4.leafFragmentation());
            assertThat(v7.avgLeafDensity()).isGreaterThan(v4.avgLeafDensity());
        }
    }

    private RoundResult runRound(Connection connection, Variant variant) throws SQLException {
        execute(connection, "DROP TABLE IF EXISTS " + variant.table);
        execute(connection, "CREATE TABLE " + variant.table
                + " (id uuid PRIMARY KEY, created_at timestamptz NOT NULL, payload text NOT NULL)");
        execute(connection, "CHECKPOINT");

        UUID[] ids = new UUID[ROWS];
        String walBefore = queryString(connection, "SELECT pg_current_wal_lsn()::text");
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);

        long start = System.nanoTime();
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + variant.table + " (id, created_at, payload) VALUES (?, ?, ?)")) {
            for (int i = 0; i < ROWS; i++) {
                UUID id = variant.generator.get();
                ids[i] = id;
                insert.setObject(1, id);
                insert.setObject(2, createdAt);
                insert.setString(3, PAYLOAD);
                insert.addBatch();
                if ((i + 1) % BATCH_SIZE == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            insert.executeBatch();
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
        long insertMillis = (System.nanoTime() - start) / 1_000_000;
        long walBytes = queryLong(connection,
                "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + walBefore + "'::pg_lsn)::bigint");

        execute(connection, "VACUUM ANALYZE " + variant.table);
        double lookupMicros = measureLookups(connection, variant, ids);
        return new RoundResult(variant, insertMillis, walBytes, lookupMicros);
    }

    /**
     * 무작위로 고른 저장 ID {@value #LOOKUPS}개를 임시 테이블에 넣고, 한 쿼리 안에서 행마다 PK 인덱스를 탐색하게 한다.
     * 해시·머지 조인을 끄면 중첩 루프 + 인덱스 스캔이 되어 "PK 단건 조회 × N"과 같은 작업이 된다.
     */
    private double measureLookups(Connection connection, Variant variant, UUID[] ids) throws SQLException {
        Random random = new Random(42);
        execute(connection, "DROP TABLE IF EXISTS lookup_keys");
        execute(connection, "CREATE TEMP TABLE lookup_keys (id uuid NOT NULL)");
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lookup_keys (id) VALUES (?)")) {
            for (int i = 0; i < LOOKUPS; i++) {
                insert.setObject(1, ids[random.nextInt(ids.length)]);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
        execute(connection, "ANALYZE lookup_keys");

        execute(connection, "SET enable_hashjoin = off");
        execute(connection, "SET enable_mergejoin = off");
        try {
            String explain = "EXPLAIN (ANALYZE, TIMING OFF, FORMAT JSON) "
                    + "SELECT count(t.payload) FROM lookup_keys k JOIN " + variant.table + " t ON t.id = k.id";
            queryString(connection, explain);
            String plan = queryString(connection, explain);
            if (!plan.contains(variant.table + "_pkey")) {
                throw new IllegalStateException("PK 인덱스 탐색 계획이 아닙니다: " + plan);
            }
            return executionMillis(plan) * 1_000 / LOOKUPS;
        } finally {
            execute(connection, "RESET enable_hashjoin");
            execute(connection, "RESET enable_mergejoin");
        }
    }

    private static double executionMillis(String plan) {
        Matcher matcher = EXECUTION_TIME.matcher(plan);
        if (!matcher.find()) {
            throw new IllegalStateException("실행 시간을 찾지 못했습니다: " + plan);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private IndexStats indexStats(Connection connection, Variant variant) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT index_size, leaf_pages, avg_leaf_density, leaf_fragmentation"
                             + " FROM pgstatindex('" + variant.table + "_pkey')")) {
            rs.next();
            return new IndexStats(rs.getLong(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4));
        }
    }

    private String report(Connection connection, List<RoundResult> rounds, Map<Variant, IndexStats> stats)
            throws SQLException {
        IndexStats v4 = stats.get(Variant.UUID_V4);
        IndexStats v7 = stats.get(Variant.UUID_V7);
        StringBuilder out = new StringBuilder();
        out.append("## UUID PK 벤치마크 (UUIDv4 vs UUIDv7)\n\n");
        out.append("- PostgreSQL ").append(queryString(connection, "SHOW server_version"))
                .append(", shared_buffers ").append(queryString(connection, "SHOW shared_buffers")).append('\n');
        out.append("- 행 ").append(String.format(Locale.ROOT, "%,d", ROWS))
                .append(", 커밋당 ").append(String.format(Locale.ROOT, "%,d", BATCH_SIZE)).append("행, ")
                .append(ROUNDS).append("회 반복(순서 교대) 중앙값\n");
        out.append("- INSERT 시간은 JDBC 왕복·ID 생성 포함, 조회는 서버 내부 PK 인덱스 탐색 ")
                .append(String.format(Locale.ROOT, "%,d", LOOKUPS)).append("회의 실행 시간\n");
        out.append("- 클라이언트 CPU ").append(Runtime.getRuntime().availableProcessors()).append("코어, ")
                .append(System.getProperty("os.name")).append(", Docker Desktop\n\n");
        out.append("| 지표 | UUIDv4 | UUIDv7 | v7 기준 차이 |\n| --- | ---: | ---: | ---: |\n");
        row(out, "INSERT 시간(ms)", median(rounds, Variant.UUID_V4, RoundResult::insertMillis),
                median(rounds, Variant.UUID_V7, RoundResult::insertMillis), "%.0f");
        row(out, "WAL 생성량(MB)", median(rounds, Variant.UUID_V4, r -> r.walBytes() / 1_048_576.0),
                median(rounds, Variant.UUID_V7, r -> r.walBytes() / 1_048_576.0), "%.1f");
        row(out, "PK 인덱스 크기(MB)", v4.indexBytes() / 1_048_576.0, v7.indexBytes() / 1_048_576.0, "%.1f");
        row(out, "리프 페이지 수", v4.leafPages(), v7.leafPages(), "%.0f");
        row(out, "리프 평균 채움률(%)", v4.avgLeafDensity(), v7.avgLeafDensity(), "%.1f");
        row(out, "리프 단편화(%)", v4.leafFragmentation(), v7.leafFragmentation(), "%.1f");
        row(out, "PK 조회 1건당(µs, 서버 내부)", median(rounds, Variant.UUID_V4, RoundResult::lookupMicros),
                median(rounds, Variant.UUID_V7, RoundResult::lookupMicros), "%.2f");
        return out.toString();
    }

    private static void row(StringBuilder out, String name, double v4, double v7, String format) {
        String diff = v4 == 0 ? "-" : String.format(Locale.ROOT, "%+.0f%%", (v7 - v4) / v4 * 100);
        out.append("| ").append(name)
                .append(" | ").append(String.format(Locale.ROOT, format, v4))
                .append(" | ").append(String.format(Locale.ROOT, format, v7))
                .append(" | ").append(diff).append(" |\n");
    }

    private static double median(List<RoundResult> rounds, Variant variant, ToDoubleFunction<RoundResult> metric) {
        double[] values = rounds.stream()
                .filter(r -> r.variant() == variant)
                .mapToDouble(metric)
                .sorted()
                .toArray();
        return values[values.length / 2];
    }

    private static Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", postgres.getUsername());
        properties.setProperty("password", postgres.getPassword());
        properties.setProperty("reWriteBatchedInserts", "true");
        return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
