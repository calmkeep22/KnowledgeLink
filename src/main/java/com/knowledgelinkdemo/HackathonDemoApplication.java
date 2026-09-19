package com.knowledgelinkdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DB·실제 Jira/GitHub 연결 없이 가명 mock → 요약 → 단일 화면만 실행하는 해커톤 부트스트랩.
 * 운영 애플리케이션의 인증·영속성 구성과 섞이지 않도록 별도 루트 패키지에서 필요한 계층만 스캔한다.
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.knowledgelink.demo",
                "com.knowledgelink.common.error",
                "com.knowledgelink.common.web"
        },
        excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
        })
public class HackathonDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(HackathonDemoApplication.class, args);
    }
}
