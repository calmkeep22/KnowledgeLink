# Changelog

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르고, 버전은 [Semantic Versioning](https://semver.org/lang/ko/)을 따른다.
테스트 ID(T01 등)는 [테스트 체크리스트](docs/knowledge-link/06-test-checklist.md)를 가리킨다.

## [Unreleased]

### Added
- 프로젝트 기반: Java 21, Spring Boot 4.1, 패키지 `com.knowledgelink`, Flyway 마이그레이션(V1~V3), 로컬 PostgreSQL(pgvector) `compose.yaml`.
- 인증(F01): 세션·CSRF 기반 JSON 로그인/로그아웃, `GET /auth/me`, 초기 비밀번호 변경(`POST /auth/password`).
  - 로그인 시 세션 ID를 바꾸고 CSRF 토큰을 폐기한다.
  - 초기 비밀번호를 바꾸기 전에는 인증 API 외 모든 API를 막는다.
  - 비밀번호를 바꾸면 같은 계정의 다른 세션은 다음 요청에서 끊긴다.
  - 계정이 비활성화되면 로그인 중인 세션도 다음 요청에서 끊긴다.
- 공통 오류 응답(`code/message/requestId/retryable/details`)과 요청 ID 필터.
- 계정 등록 운영 스크립트(`seed` 프로필, `config/seed.example.yml`).
- 단위 테스트와 Testcontainers 통합 테스트 분리(`test` / `integrationTest`).
- UUIDv7 애플리케이션 발급 PK와 `Persistable` 저장 방식(ADR 0003). 실제 SQL을 수집해 저장 쿼리 수를 비교하는 통합 테스트.
- 테스트용 `MutableClock`과 생성 시각 기록 검증 통합 테스트: 주입한 `Clock`의 시각이 DB `created_at`에 기록되고, 수정해도 유지되며, JVM 시간대가 달라도 같은 순간으로 저장된다.
- UUIDv4·UUIDv7 PK 벤치마크(`./gradlew benchmark`, 기록: `docs/benchmarks/uuid-primary-key.md`).
- 테스트 결과 기록(`docs/testing/test-report.md`): 명세 T01 PASS 기록과 설계 검증 결과, 알려진 공백.

### Changed
- `UuidV7`이 한 JVM 안에서 단조 증가한다(RFC 9562 6.2 Method 2). 같은 밀리초 안에서는 무작위 양수를 더한다.
- 엔티티 ID는 새 엔티티를 만들 때만 발급한다. JPA 조회 경로는 ID 생성기를 거치지 않는다.

### Fixed
- 같은 밀리초에 발급된 UUIDv7의 순서가 무작위라 PK 인덱스 리프 채움률이 UUIDv4와 같던 문제(70.7% → 90.0%, 인덱스 크기 −21%).

### Security
- 없는 계정·틀린 비밀번호·비활성 계정을 같은 응답으로 처리하고, 없는 계정도 해시 비교를 수행해 응답 시간 차이를 줄였다.
- BCrypt 72바이트 한도를 넘는 비밀번호는 설정 단계에서 거절하고 로그인에서도 예외 없이 실패 처리한다.

### Notes
- 명세 대비 차이: `login_id`는 조직 내 unique보다 강한 전역 unique로 두었다(단일 조직 MVP).
- 오류 코드 `INVALID_CREDENTIALS`, `PASSWORD_CHANGE_REQUIRED`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`를 추가했다.
