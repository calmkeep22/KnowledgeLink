package com.knowledgelink.support;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate가 실행하려는 SQL을 가로채 기록한다. 기록은 호출한 스레드에서만 하므로
 * 다른 스레드(세션 정리, 이후 작업 워커)의 SQL이 섞이지 않는다.
 *
 * <p>통합 테스트 설정의 {@code hibernate.session_factory.statement_inspector}로 등록된다.
 */
public class SqlCapture implements StatementInspector {

    private static final ThreadLocal<List<String>> CAPTURED = new ThreadLocal<>();

    public static List<String> during(Runnable action) {
        List<String> statements = new ArrayList<>();
        CAPTURED.set(statements);
        try {
            action.run();
        } finally {
            CAPTURED.remove();
        }
        return List.copyOf(statements);
    }

    @Override
    public String inspect(String sql) {
        List<String> statements = CAPTURED.get();
        if (statements != null) {
            statements.add(sql);
        }
        return sql;
    }
}
