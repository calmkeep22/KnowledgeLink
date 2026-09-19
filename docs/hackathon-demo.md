# KnowledgeLink 해커톤 데모

이 데모는 가명 처리한 Jira 이슈, GitHub PR·commit mock 데이터로 두 가지 흐름을 보여 준다.

- **유사 과거 업무 검색**: 새 업무 설명을 임베딩해 해결된 과거 이슈·PR과 코사인 유사도로 비교하고, 찾은 자료만 근거로 AI가 참고할 점을 정리한다.
- **활동 요약**: 팀원·프로젝트·인수인계 관점으로 활동을 근거가 연결된 업무 맥락으로 요약한다.

실제 Jira/GitHub OAuth, 실시간 동기화, DB·pgvector 저장, 개인 순위는 데모 범위에 포함하지 않는다. 과거 업무 벡터는 기동할 때 한 번 만들어 메모리에 둔다.

## 과거 업무 출처

유사 업무 검색 대상은 `KL_DEMO_PAST_WORK_SOURCE`로 고른다.

| 값 | 자료 | 원본 링크 |
|---|---|---|
| `mock`(기본) | 가명 과거 업무 34건(이슈·PR 17쌍). 네트워크 없이 동작한다 | 열리지 않는 예시 주소 |
| `apache` | 공개 Apache Jira에서 해결된 버그와, 이를 고친 GitHub 병합 PR | 실제 Jira·GitHub 페이지 |

`apache` 모드는 저장소의 최근 병합 PR(기본 10페이지, 약 1,000건)에서 제목의 Jira 키(`KAFKA-12345:`)를 뽑고, 그 키로 Jira REST API v2를 익명 조회해 해결된 버그만 남긴다(기본 최대 200건). 이슈와 PR은 이 키로 서로 연결되고, 검색 결과에는 "이 이슈를 고친 PR"이 함께 표시된다. GitHub는 익명 호출(시간당 60회)로 충분하며 `GITHUB_TOKEN`을 주면 한도가 늘어난다. 대상 주소와 프로젝트는 운영자 설정(`KL_DEMO_JIRA_BASE_URL`, `KL_DEMO_JIRA_PROJECT`, `KL_DEMO_GITHUB_REPOSITORY`)으로만 바꾼다.

첫 검색 전(기동 직후 색인할 때) 한 번 수집하고, 성공하면 `KL_DEMO_PAST_WORK_SNAPSHOT` 경로에 저장한다. 저장본이 `KL_DEMO_PAST_WORK_SNAPSHOT_MAX_AGE`(기본 12시간)보다 새로우면 재시작 때 수집하지 않고 저장본으로 바로 시작한다. 오래됐으면 다시 수집하고, 수집이 실패하면 저장본을, 저장본도 없으면 가명 예시 데이터를 쓴다.

과거 업무 색인 임베딩은 `KL_DEMO_EMBEDDING_CACHE` 경로에 "모델 ID + 본문" 해시로 저장한다. 재시작 때는 저장본에 없는(새로 생기거나 바뀐) 항목만 공급자를 호출하고, 그 항목들은 `KL_DEMO_EMBEDDING_PARALLELISM`(기본 4)개씩 동시에 임베딩한다. 사용자 질의 임베딩은 저장하지 않는다. 화면 상단의 데이터 출처 줄에 실제 수집·저장본·예시 중 무엇인지와 수집 시각이 표시된다. 화면의 예시 질문도 출처마다 `application-demo.yml`의 `mock-examples`와 `apache.examples`에서 읽는다.

팀 현황 요약은 개인의 한 주 업무를 다루므로 실존 인물 대신 가명 시나리오 데이터를 유지한다.

## 핵심 시나리오

1. 저장소 루트에서 `./gradlew demoRun`(Windows는 `.\\gradlew.bat demoRun`)으로 DB 없는 데모 앱을 실행하고 `http://localhost:8080/demo/`를 연다.
2. **이 문제, 누가 이미 풀었을까?**에서 예시 질문(결제 중복, 로그인 풀림 등)을 누르거나 새 업무를 직접 입력하고 **비슷한 업무 찾기**를 누른다.
3. 비슷한 점과 참고할 해결 방법이 문장별 근거 링크와 함께 나오고, 검색된 과거 업무의 유사도와 이 주제를 다뤄 본 팀원이 보이는지 확인한다.
4. 화면 아래의 **요약에 사용되는 활동**에서 가명 Jira·GitHub mock 데이터와 원본 근거 링크를 확인한다.
5. **팀원별 업무**에서 팀원을 선택하고 **팀원 요약 보기**를 누른다. 완료한 일, 진행 중, 막힌 점, 다음 행동이 근거와 함께 나온다.
6. **프로젝트 전체**에서 **프로젝트 요약**과 **인수인계 요약**을 눌러 다음 담당자가 확인할 업무 맥락을 보여 준다.

발표 메시지는 다음 한 문장으로 유지한다.

> "이 문제, 우리 회사에서 이미 누가 풀지 않았을까?" KnowledgeLink는 흩어진 Jira·GitHub 기록에서 비슷한 과거 업무와 해결 방법, 경험 있는 팀원을 근거와 함께 찾아 줍니다.

화면의 인물명, 프로젝트와 활동은 공개 데모용 가명·mock 데이터다. 결과는 개인 평가나 활동량 순위가 아니며 AI 생성 문장은 원본 근거에서 다시 확인해야 한다.

## API만 확인하기

PowerShell 또는 curl에서 아래 순서로 호출한다. `memberId`와 `projectId`는 첫 번째 응답에 실제로 들어 있는 값을 사용한다.

```bash
curl -s http://localhost:8080/api/v1/demo/activities
curl -s http://localhost:8080/api/v1/demo/members/{memberId}/summary
curl -s "http://localhost:8080/api/v1/demo/projects/{projectId}/summary?mode=PROJECT"
curl -s "http://localhost:8080/api/v1/demo/projects/{projectId}/summary?mode=HANDOFF"
```

첫 응답은 `{ "activities": [...] }` 형태다. 요약 응답은 `completed`, `inProgress`, `blockers`, `nextActions`를 가지며 각 문장의 `evidenceIds`가 첫 응답의 활동 ID를 가리킨다.

유사 업무 검색은 자유 입력을 URL과 접근 로그에 남기지 않도록 POST 본문으로 보낸다. 질의는 2자 이상 500자 이하다.

검색은 두 단계다. `POST /api/v1/demo/similar-work`는 질의 확장·임베딩·순위까지만 해 1~2초 안에 과거 업무, 연결된 PR, 관련 팀원을 돌려준다. 응답의 `explanationPending`이 true이면 같은 본문으로 `POST /api/v1/demo/similar-work/explanation`을 불러 AI 설명을 받는다. 설명은 질의마다 한 번만 만들고 캐시하며, 동시에 온 요청은 진행 중인 생성을 기다린다. 화면은 검색 결과를 먼저 그리고 설명 자리에 로딩을 보인 뒤 채운다.

```bash
curl -s -X POST http://localhost:8080/api/v1/demo/similar-work \
  -H "Content-Type: application/json" \
  -d '{"query":"결제 버튼을 두 번 누르면 주문이 두 번 생성돼요"}'
```

응답의 `matches`는 유사도 순 과거 업무 상위 K개(`score`는 코사인 유사도)다. `explanation`의 `similarWork`·`suggestedApproach` 문장은 `evidenceIds`로 `matches`의 과거 업무만 가리키며, 서버가 이를 검증한다. `experiencedMembers`는 1위 유사도의 70% 이상인 결과의 담당자를 묶어 각자 가장 높은 유사도 순으로 보인 것으로, 이번 질의와 관련된 경험의 근거일 뿐 평가나 순위가 아니다.

## AI 모드

`demo` profile의 기본값은 외부 과금 없이 재현 가능한 **fake 모드**다. 요약·임베딩·설명 모두 결정적 규칙으로 동작한다. fake 임베딩은 단어와 글자 2-gram을 해시한 벡터라 의미가 아닌 어휘 겹침만 반영한다. 흐름 검증과 오프라인 발표용이며, 의미 기반 검색은 아래 공급자 모드에서 확인한다.

요약·임베딩·설명은 같은 `KL_DEMO_AI_PROVIDER`를 따른다. OpenAI 모드는 아래처럼 명시적으로만 켠다. 값은 셸 기록이나 문서에 실제 비밀값을 적지 않고 실행 환경에서 주입한다.

```text
KL_DEMO_AI_PROVIDER=openai
OPENAI_API_KEY=<secret>
OPENAI_MODEL=<model-id>
OPENAI_EMBEDDING_MODEL=<embedding-model-id>
```

같은 설정의 Spring property는 각각 `kl.demo.ai.provider`, `kl.demo.ai.openai.api-key`, `kl.demo.ai.openai.model`, `kl.demo.ai.openai.embedding-model`이다. 특정 모델명이나 비밀값은 저장소에 고정하지 않는다. API 키는 파일, Git 기록, 로그, 브라우저 번들 또는 발표 화면에 넣지 않는다.

Amazon Bedrock 모드는 AWS SDK 기본 자격 증명 체인을 사용한다. 로컬에서는 AWS 프로필을 사용할 수 있고, EC2에서는 액세스 키를 저장하지 않고 인스턴스 IAM Role에 선택한 모델의 호출 권한만 부여한다.

```text
KL_DEMO_AI_PROVIDER=bedrock
AWS_REGION=<Bedrock 모델을 사용할 리전>
BEDROCK_MODEL_ID=<해당 리전에서 접근 가능한 생성 모델 또는 inference profile ID>
BEDROCK_EMBEDDING_MODEL_ID=<Titan Text Embeddings V2 모델 ID. 예: amazon.titan-embed-text-v2:0>
```

임베딩 adapter는 Titan Text Embeddings V2의 InvokeModel 요청 형식(`inputText`, `dimensions`=512, `normalize`)만 지원한다. IAM Role에는 생성 모델의 `bedrock:InvokeModel`(Converse 호출)과 임베딩 모델의 `bedrock:InvokeModel` 권한이 모두 필요하다. Titan V2 온디맨드 가격은 입력 토큰 100만 개당 약 $0.02라, 과거 업무 34건 색인과 수천 번의 검색을 합쳐도 비용은 무시할 수준이다. 비용은 주로 설명 생성 모델에서 나온다.

Bedrock의 모델 접근과 Converse API 지원 여부는 계정·리전에서 발표 전에 확인한다. 실제 키, 세션 토큰, 모델 ID는 저장소에 커밋하지 않는다. 공급자 호출이 실패해도 서버가 자동으로 유료 호출을 반복하거나 조용히 fake 결과로 바꾸지 않는다. 발표 중 네트워크 장애에 대비한 전환은 운영자가 `KL_DEMO_AI_PROVIDER=fake`로 명시적으로 수행한다.

성공한 요약은 같은 대상·같은 활동 목록에 대해 프로세스 메모리에 보관하므로, 같은 버튼을 반복해 눌러도 공급자를 다시 호출하지 않는다. 캐시는 재시작하면 비워지고 실패는 보관하지 않는다. 공급자 실패나 허용되지 않은 근거 ID는 `503 TEMPORARY_UNAVAILABLE`로 응답하며, 원인은 서버 로그에만 남긴다. 심사 전에 세 종류 요약을 한 번씩 눌러 두면 이후 응답은 즉시 나온다.

유사 업무 검색은 공개 링크에서 누구나 자유 문장을 보낼 수 있으므로 비용 상한을 둔다.

| 설정 | 기본값 | 의미 |
|---|---|---|
| `KL_DEMO_SIMILAR_TOP_K` | 5 | 설명에 넘길 과거 업무 수 |
| `KL_DEMO_SIMILAR_MAX_PER_MINUTE` | 60 | 캐시되지 않은 새 질의가 공급자를 부를 수 있는 분당 횟수. 넘으면 503 |
| `KL_DEMO_SIMILAR_CACHE_SIZE` | 200 | 결과를 보관할 서로 다른 질의 수(LRU) |
| `KL_DEMO_SIMILAR_MIN_SCORE` | 0.32 | 1위 유사도가 이보다 낮으면 설명 모델을 부르지 않고 "비슷한 사례를 찾지 못했다"고 안내한다 |

검색 전에는 생성 모델이 질문을 영어 기술 검색어로 확장하고, 원문과 확장 검색어를 함께 임베딩한다. 자료가 영어일 때 한국어 질문의 검색 품질을 높이기 위해서다. 확장에 실패하면 원문으로 검색하며, 확장 검색어는 응답의 `expandedQuery`와 화면에 표시된다. fake 모드는 확장하지 않는다.

같은 질의는 공백 차이를 무시하고 캐시에서 바로 응답한다. 기동 직후 백그라운드에서 과거 업무 색인을 만들고, 출처별 예시 질문과 모든 팀원·프로젝트 요약을 한 번씩 호출해 캐시를 채운다(로그 `Demo cache warmed`). 실패한 항목은 처음 요청할 때 다시 만든다.

## EC2 배포 artifact

운영 애플리케이션 artifact와 섞지 않고 아래 명령으로 DB 없는 데모 전용 실행 jar를 만든다.

```bash
./gradlew demoBootJar
```

생성된 `build/libs/*-demo.jar`를 EC2에 올리고 다음 환경으로 실행한다. Java 21이 필요하다.

```text
SPRING_PROFILES_ACTIVE=demo
KL_DEMO_AI_PROVIDER=bedrock
AWS_REGION=<Bedrock 모델을 사용할 리전>
BEDROCK_MODEL_ID=<모델 또는 inference profile ID>
BEDROCK_EMBEDDING_MODEL_ID=<Titan Text Embeddings V2 모델 ID>
KL_DEMO_PAST_WORK_SOURCE=apache
KL_DEMO_PAST_WORK_SNAPSHOT=/var/lib/knowledgelink/past-work.json
KL_DEMO_EMBEDDING_CACHE=/var/lib/knowledgelink/embeddings.json
```

EC2에는 장기 AWS 액세스 키를 저장하지 않는다. 인스턴스 IAM Role에 선택한 생성·임베딩 모델 호출 권한만 부여한다. 기동 로그에서 `Past work fetched from ...`과 `Similar work index ready`를 확인한 뒤 `/actuator/health`, `/demo/`, 활동 API, 세 종류의 요약 API, 유사 업무 검색 API를 순서대로 확인한다.

## 발표 전 점검

- `/demo/`가 프론트엔드 빌드 없이 열린다. 글꼴(Pretendard)만 CDN에서 받고, 실패해도 시스템 글꼴로 동작한다.
- 예시 질문 네 개가 각각 의도한 과거 업무(결제 멱등성, 세션 공유, CSV 스트리밍, 로그 마스킹)를 가장 먼저 보여 준다.
- 유사 업무 설명의 각 문장에 과거 업무 근거가 표시되고, 관련 없는 질문에는 뚜렷한 사례가 없다고 답한다.
- 활동 목록이 최신순으로 보이고 원본 근거 링크가 새 창에서 열린다.
- 팀원·프로젝트·인수인계 요약의 네 섹션이 렌더링된다.
- 각 요약 문장에 근거가 표시된다.
- 서버를 끈 상태에서는 로딩 후 안전한 오류 문구가 표시된다.
- 키보드만으로 선택, 버튼 실행, 원본 링크 이동이 가능하다.
- 실제 조직명, 실제 사용자명, 비밀값이 화면이나 응답에 포함되지 않는다.

데모에서 외부 AI 호출을 사용한다면 발표 전에 응답 시간과 계정 사용 한도를 별도로 확인한다. fake 모드는 네트워크나 공급자 상태와 무관한 발표용 기본 경로다.
OpenAI 모드는 별도 예산·인증·요청 제한을 붙이지 않은 로컬 생존 테스트용이므로 공개 서버에 그대로 노출하지 않는다.
