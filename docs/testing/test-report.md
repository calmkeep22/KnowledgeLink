# 테스트 결과 기록

[테스트 체크리스트](../knowledge-link/06-test-checklist.md) 8장의 결과 기록 형식을 따른다. 단계가 끝날 때마다 갱신한다.
체크리스트의 T번호는 명세 수용 기준이고, 4장은 명세 밖의 설계 검증이다.

## 1. 최근 실행 요약

| 항목 | 내용 |
| --- | --- |
| 실행일 | 2026-09-15 |
| 코드 버전 | `17f5842`(2단계) 위 3단계 변경(`feat/job-engine`, 이 기록을 담은 커밋) |
| 환경 | Windows 11, JDK 21.0.5, Gradle 9.7.1, Spring Boot 4.1.1, Docker 29.3.0, Testcontainers 2.0.5, PostgreSQL 17.11(`pgvector/pgvector:pg17`) |
| 단위 테스트 `./gradlew test` | 75건 통과, 실패 0 |
| 통합 테스트 `./gradlew integrationTest` | 57건 통과, 실패 0(약 4분 30초, 첫 컨테이너 기동 포함) |
| 벤치마크 `./gradlew benchmark` | 수동 실행, [UUID PK 벤치마크 기록](../benchmarks/uuid-primary-key.md) |
| 외부 호출·비용 | 없음(Jira·GitHub·AI 호출 없음) |

실행할 때마다 Gradle HTML 보고서가 `build/reports/tests/test/index.html`, `build/reports/tests/integrationTest/index.html`에 생긴다(git에는 올리지 않는다).

## 2. 명세 수용 기준 진행

| 테스트 ID | 상태 | 담당 테스트 |
| --- | --- | --- |
| T01 | PASS | `AuthIntegrationTest`(11건). 보조: `LoginServiceTest`, `PasswordPolicyTest`, `LoginIdsTest` |
| T02 | 일부 | scope 수준만 검증: `ScopeAccessIntegrationTest`(비허용 scope·다른 조직 scope는 없는 자료와 같은 404). work item·code change·analysis·question·job ID는 해당 기능 미구현 |
| T10 | 일부 | 같은 Jira 사이트 중복 연결 거절만 검증: `SourceSchemaConstraintsIntegrationTest`. 프로젝트 간 이동은 동기화 미구현 |
| T24 | PASS | `JobLeaseRecoveryIntegrationTest`(재시작 2건) |
| T25 | 일부 | `JobLeaseRecoveryIntegrationTest`: 이전 실행기의 heartbeat·결과·커서·완료·슬롯 해제 거절과 새 소유권 유지. SENT/UNKNOWN 자동 재호출 금지는 AI 단계 |
| T32 | PASS | `JobConcurrencyIntegrationTest`(4건). 작업 handler는 테스트용 |
| T03~T09, T11~T23, T26~T31, T33, T34 | 미실행 | 해당 기능 미구현(4단계 이후) |

## 3. 수용 기준 결과 기록

### T01

```
테스트 ID / 날짜 / 코드·설정 버전: T01 / 2026-09-12 / 첫 커밋 전 작업 트리, 기본 설정 + Testcontainers PostgreSQL
fixture ID / 수행 동작 또는 장애 지점: 조직 1개, 계정 4개(초기 비밀번호 MEMBER, 변경 완료 MEMBER·ADMIN, 비활성 MEMBER) / 아래 세부 항목
예상 결과 / 실제 결과 / 외부 호출 수·비용: 세부 항목 표 / 모두 일치 / 0회·0원
PASS 또는 FAIL 또는 미실행: PASS
증거 위치 / 수정할 점: src/test/java/com/knowledgelink/auth/AuthIntegrationTest.java, build/reports/tests/integrationTest / 5장 참고
```

| 항목 | 수행 동작 | 예상 결과 | 실제 |
| --- | --- | --- | --- |
| CSRF | 토큰 없이 로그인 | 403 `CSRF_INVALID` | 일치 |
| 실패 응답 통일 | 없는 계정·틀린 비밀번호·비활성 계정으로 로그인 | 모두 401 `INVALID_CREDENTIALS`, 같은 문구 | 일치 |
| 세션 고정 방지 | 로그인 전후 세션 쿠키 비교 | 값이 바뀜 | 일치 |
| 입력 검증 | 로그인 본문에 `role` 필드 추가 | 400 `UNKNOWN_FIELD`, `details[0].field=role` | 일치 |
| 초기 비밀번호 차단 | 변경 전 `/auth/me`, `/scopes` 호출 | `me` 200, `scopes` 403 `PASSWORD_CHANGE_REQUIRED` | 일치 |
| 변경과 세션 정리 | 같은 계정으로 두 세션 로그인 후 한쪽에서 변경 | 204, 현재 세션 ID 교체, API 통과, 다른 세션 401 | 일치 |
| 필드별 오류 | 짧은 새 비밀번호 / 틀린 현재 비밀번호 | 400, `details[0].field`가 `newPassword` / `currentPassword` | 일치 |
| 로그아웃 | 로그아웃 후 `/auth/me` | 204 후 401 | 일치 |
| 비활성화 | 로그인 중 DB에서 계정 비활성화 | 다음 요청부터 401 | 일치 |
| 관리자 권한 | MEMBER·ADMIN으로 `/admin/**` 호출 | MEMBER 403 `FORBIDDEN`, ADMIN은 보안 필터 통과(API 미구현이라 404) | 일치 |
| 응답 헤더 | `/auth/me`, 401 응답 | `Cache-Control`에 `no-store`, 헤더와 본문의 requestId 일치 | 일치 |

### T24·T25·T32

```
테스트 ID / 날짜 / 코드·설정 버전: T24·T25·T32 / 2026-09-15 / feat/job-engine, 기본 kl.jobs 설정(lease 120초·heartbeat 30초·3회) + Testcontainers PostgreSQL, 실행기 자동 실행 끔
fixture ID / 수행 동작 또는 장애 지점: 조직 1~2개, Jira·GitHub scope, 테스트용 handler, 고정 시계 / 아래 세부 항목
예상 결과 / 실제 결과 / 외부 호출 수·비용: 세부 항목 표 / 모두 일치 / 0회·0원
PASS 또는 FAIL 또는 미실행: T24 PASS, T25 일부(SENT/UNKNOWN은 AI 단계), T32 PASS
증거 위치 / 수정할 점: src/test/java/com/knowledgelink/job, build/reports/tests/integrationTest / 5장 참고
```

| ID | 수행 동작 | 예상 결과 | 실제 |
| --- | --- | --- | --- |
| T24 | QUEUED로 저장한 뒤 메모리 상태가 없는 새 실행기로 한 주기 실행 | 작업 실행, SUCCEEDED, 시도 1 | 일치 |
| T24 | RUNNING 중 실행기 종료(heartbeat·완료 없음) 후 새 실행기 | lease가 남아 있으면 그대로, 만료 후 RETRY_WAIT(`LEASE_EXPIRED`)·슬롯 반납, 대기 후 다시 실행해 SUCCEEDED·시도 2 | 일치 |
| T25 | lease 만료 → 복구 → 새 실행기 재선점 후, 이전 실행기가 heartbeat·결과 저장·단계 기록·완료·재시도·실패 요청 | 모두 `LeaseLostException`, 결과 미저장, 작업·슬롯은 새 run_token 유지, 새 실행기는 정상 완료 | 일치 |
| T25 | handler 실행 중 소유권을 잃은 뒤 성공 결과 반환 | 결과 미저장, 새 소유자 유지 | 일치 |
| T25 | lease 만료 시각에 heartbeat | 거절, lease 연장 안 됨 | 일치 |
| T32 | AI 작업 8개(4종×2)를 8개 connection에서 동시에 선점 | RUNNING 1, QUEUED 7, 시도를 쓴 작업 1 | 일치 |
| T32 | 두 조직·세 scope의 SYNC·RECONCILE을 동시에 선점 | RUNNING 1 | 일치 |
| T32 | INDEX와 AI를 동시에 선점 | 둘 다 RUNNING | 일치 |
| T32 | 선점 → 실행기 사망 → 8개 connection에서 동시 복구를 3회 반복 | 매번 복구 1건·슬롯 비워짐, 마지막에 FAILED(`LEASE_EXPIRED`)·시도 3, 점유 슬롯 0 | 일치 |

## 4. 명세 밖 설계 검증

| 대상 | 테스트 | 결과 |
| --- | --- | --- |
| ADR 0003 저장 쿼리 | `PersistableSaveQueryIntegrationTest` 3건 | PASS. 새 엔티티 100건 SELECT 0·INSERT 100, merge 경로 SELECT 100·INSERT 100 |
| ADR 0003 ID 생성 | `UuidV7Test` 8건 | PASS. 같은 밀리초 안 순서, 시계 역행, 8개 스레드 40만 개 중복 없음 포함 |
| ADR 0003 인덱스 | `UuidPrimaryKeyBenchmark` | UUIDv4 대비 인덱스 −21%, 리프 채움률 71.0% → 90.0%, 단편화 49.6% → 0.0%. 시간 지표는 경향만 |
| 생성 시각 기록 | `AuditingClockIntegrationTest` 3건 | PASS. 주입한 시계 값 저장, 수정 시 유지, JVM 시간대와 무관 |
| 계정 등록 스크립트 | `AccountProvisioningIntegrationTest` 2건 | PASS. 재실행해도 결과 같음, 정책 위반 거절(메시지에 비밀번호 없음) |
| 입력 규칙 | `LoginIdsTest` 14건, `PasswordPolicyTest` 6건 | PASS |
| 로그인 서비스 | `LoginServiceTest` 5건 | PASS. 없는 계정도 해시 비교 수행, 인코더 한도 초과 입력도 같은 오류 |
| scope 접근 판단 | `ScopeAccessPolicyTest` 6건 | PASS. 볼 수 없는 scope는 403이 아니라 404, 범위를 고르지 않으면 볼 수 있는 전체, 볼 수 없는 scope가 섞이면 404, 하나도 없으면 422, 같은 요청 안에서는 한 번만 조회 |
| `GET /scopes` | `ScopeAccessIntegrationTest` 8건 | PASS. ADMIN은 켜진 scope 전부·MEMBER는 grant받은 것만, grant 없으면 빈 목록, 로그인 중 grant 회수·scope 끄기가 다음 요청에 반영, 응답에 인증 정보·URL 없음, 다른 조직·grant 없는 scope는 404 |
| 조직 경계 DB 제약 | `SourceSchemaConstraintsIntegrationTest` 5건 | PASS. 다른 조직 연결의 scope, 다른 조직 계정의 grant, 중복 grant, 같은 Jira 사이트 중복 연결, 토큰처럼 보이는 `credential_ref`와 site_id 있는 GitHub 연결을 DB가 거절 |
| 운영 스크립트(연결·scope·grant) | `SeedRunnerIntegrationTest` 3건 | PASS. 두 번 실행해도 한 번만 생성, ADMIN grant 거절, 형식이 틀리거나 없는 scope 참조 거절(메시지로 원인 확인) |
| 연결·scope 입력 규칙 | `SourceConnectionTest` 16건, `SourceScopeTest` 8건 | PASS. `credential_ref`·base URL 오류 메시지에 입력값 없음, Jira는 site_id 필수·GitHub는 금지, 표시 키 형식 |
| 작업 상태 전환 | `JobEngineIntegrationTest` 11건 | PASS. 입장월 서울 기준, scope당 활성 SYNC 하나, 다른 조직 scope 거절, 선점 시 작업·슬롯에 같은 소유권, 결과와 SUCCEEDED를 함께 저장(결과 저장 실패 시 모두 롤백), backoff 뒤 재실행·3회 소진 시 FAILED, Retry-After 존중, 영구 실패는 바로 FAILED, 슬롯 대기는 시도 미소비, 어긋난 소유 정보는 DB가 거절 |
| 재시도·실행기 설정 | `RetryPolicyTest` 5건, `JobHandlersTest` 4건, `NewJobTest` 2건, `JobPropertiesTest` 1건 | PASS. backoff 상한·jitter 범위, 종류별 슬롯, handler 중복 거절, heartbeat는 lease보다 짧음 |

## 5. 알려진 공백

- 로그인 시도 제한이 없다. 공개 배포 전에 추가한다.
- 비밀번호 변경·로그아웃 요청의 CSRF 누락 거절은 개별 테스트가 없다. 같은 CSRF 필터를 로그인 요청으로 대표 검증했다.
- 로그·오류 응답에 비밀값이 남지 않는지(T29)는 아직 자동으로 검사하지 않는다. 연결의 `credential_ref`·base URL 검증 오류가 입력값을 메시지에 싣지 않는 것만 단위 테스트로 확인했다.
- scope를 켜고 끄거나 grant를 주고 회수하는 관리 API는 아직 없다. 지금은 운영 스크립트(추가만)와 DB 직접 수정으로만 바꾼다.
- 작업 handler가 아직 없어 실행기는 테스트용 handler로만 검증했다. 운영 주기 실행(`start()`)과 실제 시간 간격의 heartbeat는 자동 테스트가 없다.
- 실행기가 정상 종료할 때도 실행 중이던 작업을 바로 반납하지 않는다. lease가 만료된 뒤(최대 120초) 복구되며 그 실행의 시도 횟수는 소비된다.
- `/jobs/{jobId}` 조회·재시도 API와 T02의 job ID 404는 아직 없다.
- 벤치마크 시간 지표는 Windows + Docker Desktop 환경의 실행 간 편차가 커서 확정 수치가 아니다.

## 6. 실행 이력

| 날짜 | 범위 | 결과 | 비고 |
| --- | --- | --- | --- |
| 2026-09-15 | 단위 75 / 통합 57 | 전부 통과 | 3단계(작업 엔진). T24·T32 PASS, T25 일부 |
| 2026-09-15 | 단위 63 / 통합 35 | 전부 통과 | 2단계(scope·grant, `GET /scopes`) 최종 |
| 2026-09-14 | 통합 3(`SeedRunnerIntegrationTest`) | 전부 통과 | 테스트 ADMIN 비밀번호 수정, 형식 오류 테스트에 메시지 검증 추가 |
| 2026-09-14 | 통합 35 | 33 통과, 2 실패 | 테스트 ADMIN 초기 비밀번호에 아이디가 들어가 계정 등록에서 먼저 실패. 같은 클래스의 형식 오류 테스트는 이 예외로 잘못 통과하던 상태 |
| 2026-09-14 | 통합 35 | 미실행 | Docker 데몬 없음 |
| 2026-09-12 | 단위 33 / 통합 19 | 전부 통과 | 단조 증가 UUIDv7, 생성자 ID 발급 반영 후 |
| 2026-09-12 | 벤치마크 2차 | 완료 | 단조 증가 UUIDv7 |
| 2026-09-12 | 벤치마크 1차 | 완료 | 같은 밀리초 안 무작위 UUIDv7. 채움률 문제 발견 |
| 2026-09-12 | 통합 19 | 전부 통과 | Docker 시작 후 첫 실행 |
| 2026-09-12 | 통합 13 | 미실행 | Docker 데몬 없음(`DockerClientProviderStrategy`) |
