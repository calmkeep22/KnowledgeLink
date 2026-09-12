# API 명세서

버전 0.2 · [기능명세서와 유즈케이스](01-functional-spec.md) · 기계 계약: OpenAPI(구현 시작 시 작성)

## 1. 공통 규칙

base path는 `/api/v1`, JSON은 camelCase, ID는 UUID, 시각은 UTC다. 세션 cookie와 CSRF로 인증한다. 사용자·workspace·scope 접근은 서버에서 판단한다. 초기 비밀번호 변경 전에는 인증·비밀번호 변경 API만 허용한다. 생성 201, 작업 접수 202, 정상 조회 200, 본문 없는 성공 204다.

| 항목 | 계약 |
| --- | --- |
| 세션 | 운영 Secure/HttpOnly/SameSite=Lax `__Host-SESSION`, 로그인 후 세션 회전 |
| CSRF | GET /auth/csrf의 X-CSRF-TOKEN을 login 포함 모든 변경 요청에 전달 |
| 중복 | 작업 접수 endpoint에 Idempotency-Key(UUID). 같은 요청은 같은 결과, 다른 본문 재사용은 409 |
| 수정 충돌 | 추정 연결 결정은 조회 ETag를 If-Match로 전송. 누락 428, 다른 revision 412 |
| 목록 | limit 기본 20/최대 100, cursor. 권한 필터를 먼저 적용 |
| 접근 불가 | 없는 자료와 권한 밖 자료는 모두 404 RESOURCE_NOT_FOUND |
| 민감 응답 | no-store. 오류·로그에 토큰·세션·질문 원문·마스킹 전 원문 금지 |

## 2. MVP에서 구현할 API 21개

L은 로그인 사용자, S는 대상 자료의 scope 접근(MEMBER grant 또는 ADMIN), A는 ADMIN이다. 모든 권한은 같은 workspace 안에서만 유효하다.

| Method / path | 권한 | 입력 → 결과 |
| --- | --- | --- |
| GET /auth/csrf | 익명 가능 | CSRF token |
| POST /auth/login | 익명+CSRF | loginId/password → 사용자 |
| POST /auth/logout | L | 세션 종료 204 |
| GET /auth/me | L | role·mustChangePassword |
| POST /auth/password | L | currentPassword/newPassword → 204 |
| GET /scopes | L | 내가 볼 수 있는 scope·종류·마지막 동기화 시각 |
| POST /analyses | L | issueKey 또는 text, scopeIds? → 202 analysisId/jobId/status |
| GET /analyses/{analysisId} | 본인/A | 상태·요청 해석·유사 업무·연결 PR·개발자·요약·인용 |
| POST /analyses/{analysisId}/feedback | 본인 | targetType/targetId/verdict/note? → 204 |
| POST /questions | L | question, scopeIds? → 202 questionId/jobId/status |
| GET /questions/{questionId} | 본인/A | 상태·disposition·답·인용·개발자 |
| GET /work-items/{workItemId} | S | 이슈 내용·최근 댓글·연결 PR·참여자 |
| GET /work-items/{workItemId}/knowledge-card | S | 현재 접근 가능한 READY 카드 또는 재생성 필요 상태, 카드가 없으면 404 |
| POST /work-items/{workItemId}/knowledge-card | S | 본문 없음 → 현재 접근 범위에 맞고 전체 근거가 허용된 최신 READY면 200, 없으면 권한·예산 검사 후 202 jobId |
| GET /code-changes/{codeChangeId} | S | PR 내용·커밋 메시지·변경 파일·연결 이슈 |
| GET /jobs/{jobId} | 요청자/A, SYNC·INDEX는 A | 상태·단계·실패·canRetry·결과 ID |
| POST /jobs/{jobId}/retry | 위와 동일 | 본문 없음 → 202 같은 jobId |
| GET /admin/connections | A | 연결·scope 상태, 수집·색인 건수, 마지막 오류 코드 |
| POST /admin/scopes/{scopeId}/sync | A | mode=INCREMENTAL/RECONCILE → 202 jobId |
| GET /admin/link-suggestions | A | SUGGESTED 연결 목록·근거·ETag |
| POST /admin/link-suggestions/{linkId}/decision | A | decision=CONFIRM/REJECT, If-Match → 최신 연결 |

분석·질문 결과의 모든 이슈·PR·사람·인용은 조회 시점의 권한과 visibility로 다시 거른다. 걸러진 항목은 "숨겨진 항목 N개"처럼 개수로도 드러내지 않는다. 사람은 보이는 근거가 남아 있을 때만 결과에 포함한다. 연결 PR은 이슈와 PR 양쪽 scope에 접근할 수 있을 때만 포함한다.

## 3. 주요 요청·응답

생성물별 전체 입력 source_basis를 검사한다. 하나라도 접근 불가/HIDDEN이면 해당 본문·인용을 통째로 제외한다. summary·interpretation·공용 카드는 displayStatus=VISIBLE/STALE/REGENERATION_REQUIRED와 content를 사용한다. REGENERATION_REQUIRED는 content=null, citations=[]와 고정 안내만 반환하며 숨긴 sourceIds·원본 버전·개수는 반환하지 않는다. 관련도 이유가 차단된 후보는 similarItems에서 제외하고 사람 근거도 재계산한다. 입력 이슈 자체에 접근할 수 없는 분석은 404다.

질문은 answerDisplayStatus와 answer=null/citations=[]를 사용하고 저장된 disposition·job status는 바꾸지 않는다. 기존 ANSWERED의 인용 1개 조건은 답변이 VISIBLE/STALE일 때 적용한다. 공용 카드 GET은 대상 이슈가 허용되면 차단 카드 상태를 200으로 알릴 수 있으며, 카드 자체가 없을 때만 404다. STALE 공용 카드도 content=null로 반환한다. 재생성은 기존 POST /analyses, /questions, /work-items/{id}/knowledge-card에 새 Idempotency-Key로 요청한다. 분석·질문은 새 resourceId를 만들고 자동 유료 재생성은 하지 않는다.

분석 요청은 issueKey와 text 중 하나만 받는다. 둘 다 있거나 둘 다 없으면 400이다. scopeIds를 비우면 내가 볼 수 있는 전체 scope다.

```json
{"issueKey":"PAY-381","scopeIds":[]}
```

issueKey를 받으면 서버가 Jira에서 최신 이슈를 조회해 소속 scope를 확인한다. 요청자가 그 scope에 접근할 수 없으면 없는 키와 같은 404다. 입력 이슈 자신과 명시·확정 연결된 PR은 결과에서 제외한다. 클라이언트가 결과 항목·점수·scope 권한 여부를 보내면 400이다.

```json
{
  "analysisId":"11111111-1111-4111-8111-111111111111",
  "status":"SUCCEEDED",
  "basisSyncedAt":"2026-09-12T01:20:00Z",
  "interpretation":{"displayStatus":"VISIBLE","content":{"domain":"결제","problem":"외부 API 응답 지연","techniques":["Retry","Backoff"],"generatedBy":"AI"}},
  "similarItems":[{
    "type":"WORK_ITEM",
    "id":"22222222-2222-4222-8222-222222222222",
    "source":{"issueKey":"PAY-142"},
    "title":"결제 승인 API Timeout Retry 처리",
    "relevance":"HIGH",
    "reasons":[{"text":"같은 외부 결제 API 응답 지연으로 인한 Timeout을 다룹니다.","evidenceIds":["33333333-3333-4333-8333-333333333333"]}],
    "links":[{"codeChangeId":"44444444-4444-4444-8444-444444444444","repo":"acme/payment","number":1832,"kind":"EXPLICIT"}]
  }],
  "people":[{
    "personId":"55555555-5555-4555-8555-555555555555",
    "displayName":"김민수",
    "evidence":[{"type":"WORK_ITEM","id":"22222222-2222-4222-8222-222222222222","role":"ASSIGNEE"},
                {"type":"CODE_CHANGE","id":"44444444-4444-4444-8444-444444444444","role":"PR_AUTHOR"}]
  }],
  "summary":{"displayStatus":"VISIBLE","content":{"text":"PAY-142와 PR #1832를 먼저 확인하세요."},"citations":["22222222-2222-4222-8222-222222222222","44444444-4444-4444-8444-444444444444"]}
}
```

예시의 ID·저장소·인물은 가상이다. relevance는 HIGH/MEDIUM/LOW 구간이며 원점수는 응답하지 않는다. 결과 카드의 links.kind는 EXPLICIT/CONFIRMED만 사용한다. 상세·관리자 화면에서 SUGGESTED를 별도 표시한다. 후보가 없으면 similarItems와 people은 빈 배열이고 status는 SUCCEEDED다.

similarItems의 공통 필수 필드는 type/id/title/relevance/reasons/source다. WORK_ITEM은 source.issueKey와 links를 가지며 CODE_CHANGE는 source.repository/source.number를 가진다. OpenAPI는 type discriminator와 oneOf로 두 유형을 구분한다. 피드백 targetType도 WORK_ITEM/CODE_CHANGE를 허용하고 해당 요청 결과에 속하는지 검사한다.

```json
{"type":"CODE_CHANGE","id":"66666666-6666-4666-8666-666666666666","title":"결제 재시도 처리","relevance":"HIGH","reasons":[{"text":"외부 API 지연 시 재시도를 처리합니다.","evidenceIds":["77777777-7777-4777-8777-777777777777"]}],"source":{"repository":"sample/payment","number":42}}
```

명시·확정 연결 이슈가 보이면 PR은 이슈 카드에 묶으며 단독 PR로 중복 반환하지 않는다. 여러 이슈에 연결된 PR은 각 이슈의 links에는 나타날 수 있다. 추정 연결은 묶음 근거로 쓰지 않는다. 연결 이슈가 권한 밖이면 허용된 PR만 단독 후보로 취급하고 숨긴 연결의 존재는 알리지 않는다.

질문 성공 disposition은 ANSWERED 또는 INSUFFICIENT_EVIDENCE이며 후자도 정상 처리다. ANSWERED는 인용이 1개 이상이다. 작업 실패를 조회하는 HTTP 응답은 200 안에 FAILED와 안전한 failure를 담는다.

요약 카드 응답은 problem/cause/solution[]/technologies/domain과 문장별 evidenceIds, needsReview 문장 목록, 기준 source_hash 시각을 담는다. 카드 근거에는 명시·확정 연결 PR만 쓰며 추정 연결 PR은 쓰지 않는다.

## 4. 중복·오류

Idempotency-Key 범위는 사용자+operation+대상+key이며 30일 보관을 제안한다. 재전송에도 현재 권한을 확인한다. 예산 거절은 job·예약·건수 변경을 모두 롤백한다.

| HTTP | 대표 code | 사용자 행동 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / UNKNOWN_FIELD / INVALID_ISSUE_KEY | 입력 형식 수정 |
| 401 / 403 | UNAUTHENTICATED / FORBIDDEN / CSRF_INVALID | 로그인·역할·토큰 확인 |
| 404 | RESOURCE_NOT_FOUND | 없는 자료·권한 밖 자료·비허용 scope 이슈 키를 통합 |
| 409 | ACTIVE_SYNC_EXISTS / INVALID_STATE / IDEMPOTENCY_CONFLICT | 기존 작업·상태 확인 |
| 412 / 428 | REVISION_CONFLICT / PRECONDITION_REQUIRED | 최신 ETag 확인 |
| 422 | INPUT_LIMIT_EXCEEDED / NO_ACCESSIBLE_SCOPE | 입력·범위 수정 |
| 429 | BUDGET_EXCEEDED / MONTHLY_QUOTA_EXCEEDED / RATE_LIMITED | 예산 확인, rate limit만 Retry-After 후 재요청 |
| 503 | PAID_FEATURE_DISABLED / SOURCE_UNAVAILABLE / TEMPORARY_UNAVAILABLE | 설정·원본 서비스·장애 확인 |

오류 객체는 code/message/requestId/retryable/details 배열이다. 작업 실패 코드는 SOURCE_AUTH_FAILED(재시도 안 함), SOURCE_RATE_LIMITED(대기 후 재시도), AI_OUTPUT_INVALID, PROVIDER_USAGE_UNKNOWN(대조 전 수동 재시도도 차단)을 사용한다.

외부 원본으로 가는 링크는 Jira·GitHub의 원래 URL이며 원본 서비스가 다시 권한을 검사한다. 이 서비스는 원본 파일이나 코드를 대신 내려주지 않는다.

## 5. 후속 API와 계약 관리

`/admin/grants`, `/admin/identities`, `/admin/people/{personId}/recommendable`, `/webhooks/jira`, `/webhooks/github`(서명 검증 필수)는 `x-implementation-stage: later`로 표시하고 관리 화면·실시간 수집을 만들 때 구현한다. 개인 프로필·개발자 순위 API는 만들지 않는다.

OpenAPI 작성 → 검증기 실행 → 계약 테스트 순서로 관리한다. 실제 구현 상태는 README에 기록한다.

관련: [AI 처리 명세서](05-ai-processing.md) · [테스트 체크리스트](06-test-checklist.md)
