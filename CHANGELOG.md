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
- scope·grant 접근 모델(V4): `source_connection`·`source_scope`·`scope_grant`. scope는 같은 조직의 연결에만, grant는 같은 조직의 계정·scope 사이에만 만들 수 있도록 `(id, workspace_id)` 복합 FK로 DB가 보장한다.
- `GET /scopes`: ADMIN은 조직의 켜진 scope 전부, MEMBER는 grant받은 켜진 scope만 본다. 응답에 연결의 인증 정보·URL은 없다.
- scope 접근 판단(`ScopeAccessPolicy`): 권한 밖 scope는 없는 자료와 같은 404, 볼 수 있는 scope가 하나도 없으면 422 `NO_ACCESSIBLE_SCOPE`.
  - 볼 수 있는 scope는 요청 안에서만 한 번 계산해 두므로 grant 회수·scope 끄기가 다음 요청에 바로 반영된다.
- 운영 스크립트가 연결·scope·grant도 등록한다(`connections`, `grants`). 재실행해도 결과가 같고 삭제는 하지 않으며, ADMIN에 대한 grant는 설정 오류로 거절한다.
- 작업 엔진(V5): `job`·`execution_slot`. 슬롯은 서버 전체에 SYNC·INDEX·AI 세 개이고 슬롯마다 한 작업만 실행한다.
  - 선점은 슬롯 확보와 QUEUED → RUNNING 전환을 한 transaction에서 한다. 슬롯을 못 잡으면 QUEUED로 남고 시도 횟수를 쓰지 않는다.
  - lease 120초, 30초마다 연장. 결과·커서 저장, 완료·실패 전환, 슬롯 반납은 작업과 슬롯 양쪽의 run_token과 유효한 lease를 확인한 뒤에만 된다.
  - lease가 만료되면 RETRY_WAIT(시도를 다 썼으면 FAILED)로 복구하고 새 run_token으로 다시 선점한다. 만료된 lease는 되살리지 않는다.
  - 재시도는 최초 포함 3회, 30초~300초 exponential backoff + jitter. Retry-After가 더 길면 그만큼 기다린다.
  - scope당 활성 SYNC는 하나다. 이미 있으면 새로 만들지 않고 그 작업을 돌려준다.
  - 작업 실행기(`kl.jobs.*`)는 주기마다 복구 → 재시도 승격 → 빈 슬롯 선점 → 실행을 하고, 실행 중에는 heartbeat를 보낸다. 아직 작업 handler가 없어 실제로 실행되는 작업은 없다.
- `GET /jobs/{jobId}`: 상태·단계·시도 횟수·실패 코드·`canRetry`·결과 ID를 돌려준다. 요청한 본인과 같은 조직 ADMIN만 볼 수 있고, 요청자가 없는 시스템 작업(동기화·색인 등)은 ADMIN만 본다. 권한 밖 작업은 없는 작업과 같은 404다. 응답에 소유자·run_token·lease는 담지 않는다.
- `POST /jobs/{jobId}/retry`: 실패한 작업만 같은 jobId로 다시 큐에 넣고 누적 시도와 입장월을 유지한다(202). 사용량이 불명인 실패(`PROVIDER_USAGE_UNKNOWN`)는 막고, 같은 scope에 활성 SYNC가 있으면 409 `ACTIVE_SYNC_EXISTS`다.
- V6: `job.requested_by`. 같은 조직의 계정만 요청자가 될 수 있도록 `(requested_by, workspace_id)` 복합 FK를 걸었다.
- 관측(ADR 0005): Micrometer 지표와 OpenTelemetry 트레이스를 OTLP로 내보낸다. 기본은 끄고 `otel` 프로필에서 켠다. 로컬은 compose `observability` 프로필의 `grafana/otel-lgtm`으로 본다.
  - 작업 엔진 지표 `kl.jobs.*`: 선점 수, 대기 시간, 종류·결과별 실행 시간, lease 만료 복구 수, 상태별 작업 수, 슬롯 사용 여부.
- Jira 이슈·댓글 증분 동기화(F02, V7 `work_item`). SYNC handler가 페이지마다 이슈와 cursor를 한 transaction에 저장하고, 다음 실행은 cursor보다 10분 앞에서 다시 읽는다. 외부 호출 중에는 DB transaction을 잡지 않는다.
  - 429·일시 오류는 같은 cursor에서 재개(RETRY_WAIT), 인증·설정 오류는 연결을 ERROR로 바꾸고 재시도하지 않는다.
- Jira Cloud adapter: REST API v3 `GET /rest/api/3/search/jql`을 nextPageToken으로 넘긴다. `kl.jira.enabled=true`일 때만 adapter와 SYNC handler를 함께 등록한다(기본 꺼짐).
  - 인증은 서비스 계정 이메일과 API 토큰의 Basic 인증이다. 연결의 `credential_ref`가 가리키는 환경 변수에 `이메일:API토큰`으로 둔다.
  - JQL 날짜는 분 단위이고 서비스 계정 시간대로 해석되므로, 계정 시간대(`/rest/api/3/myself`)를 읽어 cursor 시각을 그 시간대의 분으로 내린다. 시간대를 모르면 추측하지 않고 설정 오류로 멈춘다.
  - 설명·댓글의 ADF를 평문으로 바꾼다. 코드 블록과 링크 주소는 남기고 첨부는 뺀다.
  - 검색 결과에 댓글이 다 오지 않은 이슈만 댓글 API로 최근 20개를 다시 읽는다.
  - 429는 `Retry-After`(초), 없으면 `X-RateLimit-Reset` 시각까지 기다린다. 401·403은 인증 오류, 400·404 등은 설정 오류, 408·5xx·연결 실패는 일시 오류다.

### Changed
- `UuidV7`이 한 JVM 안에서 단조 증가한다(RFC 9562 6.2 Method 2). 같은 밀리초 안에서는 무작위 양수를 더한다.
- 엔티티 ID는 새 엔티티를 만들 때만 발급한다. JPA 조회 경로는 ID 생성기를 거치지 않는다.
- 운영 DB를 서버 1대의 docker compose PostgreSQL에서 Amazon RDS for PostgreSQL 17(Single-AZ)로 바꿨다(ADR 0004). 백업은 pg_dump→S3 대신 RDS 자동 백업과 시점 복구를 쓴다. 로컬·테스트는 계속 `pgvector/pgvector:pg17` 이미지를 쓴다.
- 명세의 후속 범위에서 Prometheus를 뺐다. 관측 스택을 먼저 도입해 이후 인프라 확장의 측정 근거로 쓴다(ADR 0005).

### Fixed
- 같은 밀리초에 발급된 UUIDv7의 순서가 무작위라 PK 인덱스 리프 채움률이 UUIDv4와 같던 문제(70.7% → 90.0%, 인덱스 크기 −21%).

### Security
- 없는 계정·틀린 비밀번호·비활성 계정을 같은 응답으로 처리하고, 없는 계정도 해시 비교를 수행해 응답 시간 차이를 줄였다.
- BCrypt 72바이트 한도를 넘는 비밀번호는 설정 단계에서 거절하고 로그인에서도 예외 없이 실패 처리한다.
- 연결의 `credential_ref`는 환경 변수 이름 형식만 받는다(애플리케이션 검증과 DB CHECK 둘 다). 토큰 값을 잘못 넣으면 거절하고, 오류 메시지에 그 값을 싣지 않는다.
- Jira adapter는 인증 헤더를 https(로컬 테스트 서버만 http)로만 보내고 redirect를 따라가지 않는다. JQL에는 숫자 project ID만 넣는다.

### Notes
- 명세 대비 차이: `login_id`는 조직 내 unique보다 강한 전역 unique로 두었다(단일 조직 MVP).
- 오류 코드 `INVALID_CREDENTIALS`, `PASSWORD_CHANGE_REQUIRED`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`, `NO_ACCESSIBLE_SCOPE`, `ACTIVE_SYNC_EXISTS`를 추가했다.
- 명세 대비 차이: `source_connection.name`(조직 안 unique)을 추가했다. 운영 스크립트의 grant가 `연결이름:표시키`로 scope를 가리키는 데 쓴다. `source_scope.sync_cursor`, `last_reconciled_at`은 동기화 단계에서 추가한다.
- 명세 대비 차이: `job`의 대상 ID를 `scope_id`(SYNC·RECONCILE 대상, 같은 조직 scope FK)와 `target_id`(그 밖의 대상)로 나눴다. `residual_micro_usd`와 복구 시 비용 상태(SENT/UNKNOWN) 반영은 AI 단계에서 추가한다.
- 명세 대비 차이: `job.requested_by`를 추가했다. 명세 ERD의 job에는 없지만, `GET /jobs/{jobId}`의 "요청자 본인" 권한을 분석·질문 행 없이 판단하려면 필요하다.
