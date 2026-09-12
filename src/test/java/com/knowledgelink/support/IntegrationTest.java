package com.knowledgelink.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Testcontainers PostgreSQL을 쓰는 통합 테스트. {@code ./gradlew integrationTest}로 실행한다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("integration")
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.knowledgelink.support.SqlCapture")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockConfig.class, TestFixtures.class})
public @interface IntegrationTest {
}
