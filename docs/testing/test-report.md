# 테스트 결과 기록

[테스트 체크리스트](../knowledge-link/06-test-checklist.md) 8장의 결과 기록 형식을 따른다. 단계가 끝날 때마다 갱신한다.
체크리스트의 T번호는 명세 수용 기준이고, 4장은 명세 밖의 설계 검증이다.

## 1. 최근 실행 요약

| 항목 | 내용 |
| --- | --- |
| 실행일 | 2026-09-12 |
| 코드 버전 | 첫 커밋 전 작업 트리(커밋 후 해시로 바꾼다) |
| 환경 | Windows 11, JDK 21.0.5, Gradle 9.7.1, Spring Boot 4.1.1, Docker 29.3.0, Testcontainers 2.0.5, PostgreSQL 17.11(`pgvector/pgvector:pg17`) |
| 단위 테스트 `./gradlew test` | 33건 통과, 실패 0 |
| 통합 테스트 `./gradlew integrationTest` | 19건 통과, 실패 0(8분 47초) |
| 벤치마크 `./gradlew benchmark` | 수동 실행, [UUID PK 벤치마크 기록](../benchmarks/uuid-primary-key.md) |
| 외부 호출·비용 | 없음(Jira·GitHub·AI 호출 없음) |

실행할 때마다 Gradle HTML 보고서가 `build/reports/tests/test/index.html`, `build/reports/tests/integrationTest/index.html`에 생긴다(git에는 올리지 않는다).

## 2. 명세 수용 기준 진행

| 테스트 ID | 상태 | 담당 테스트 |
| --- | --- | --- |
| T01 | PASS | `AuthIntegrationTest`(11건). 보조: `LoginServiceTest`, `PasswordPolicyTest`, `LoginIdsTest` |
| T02~T34 | 미실행 | 해당 기능 미구현(2단계 이후) |

## 3. T01 결과 기록

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

## 5. 알려진 공백

- 로그인 시도 제한이 없다. 공개 배포 전에 추가한다.
- 비밀번호 변경·로그아웃 요청의 CSRF 누락 거절은 개별 테스트가 없다. 같은 CSRF 필터를 로그인 요청으로 대표 검증했다.
- 로그·오류 응답에 비밀값이 남지 않는지(T29)는 아직 자동으로 검사하지 않는다.
- 벤치마크 시간 지표는 Windows + Docker Desktop 환경의 실행 간 편차가 커서 확정 수치가 아니다.

## 6. 실행 이력

| 날짜 | 범위 | 결과 | 비고 |
| --- | --- | --- | --- |
| 2026-09-12 | 단위 33 / 통합 19 | 전부 통과 | 단조 증가 UUIDv7, 생성자 ID 발급 반영 후 |
| 2026-09-12 | 벤치마크 2차 | 완료 | 단조 증가 UUIDv7 |
| 2026-09-12 | 벤치마크 1차 | 완료 | 같은 밀리초 안 무작위 UUIDv7. 채움률 문제 발견 |
| 2026-09-12 | 통합 19 | 전부 통과 | Docker 시작 후 첫 실행 |
| 2026-09-12 | 통합 13 | 미실행 | Docker 데몬 없음(`DockerClientProviderStrategy`) |
