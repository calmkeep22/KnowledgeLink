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
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.knowledgelink.support.SqlCapture",
        // 작업 실행기는 끄고, 테스트가 엔진·실행기를 직접 호출해 시점을 통제한다.
        "kl.jobs.worker.enabled=false"})
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockConfig.class, TestFixtures.class})
public @interface IntegrationTest {
}
