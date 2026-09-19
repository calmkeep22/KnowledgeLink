# KnowledgeLink 해커톤 데모

이 데모는 가명 처리한 Jira 이슈, GitHub PR·commit mock 데이터를 업무 맥락으로 변환하는 최소 흐름을 보여 준다. 실제 Jira/GitHub OAuth, 실시간 동기화, DB·벡터 검색, 개인 순위는 데모 범위에 포함하지 않는다.

## 핵심 시나리오

1. 저장소 루트에서 `./gradlew demoRun`(Windows는 `.\\gradlew.bat demoRun`)으로 DB 없는 데모 앱을 실행하고 `http://localhost:8080/demo/`를 연다.
2. 화면 아래의 **요약에 사용되는 활동**에서 가명 Jira·GitHub mock 데이터와 원본 근거 링크를 확인한다.
3. **팀원별 업무**에서 팀원을 선택하고 **팀원 요약 보기**를 누른다.
4. 완료한 일, 진행 중, 막힌 점, 다음 행동이 문장별 근거 링크와 함께 나오는지 확인한다.
5. **프로젝트 전체**에서 프로젝트를 선택하고 **프로젝트 요약**을 누른다.
6. 같은 프로젝트에서 **인수인계 요약**을 눌러 다음 담당자가 확인할 업무 맥락을 보여 준다.

발표 메시지는 다음 한 문장으로 유지한다.

> 관리자가 Jira와 GitHub를 일일이 확인하는 대신, KnowledgeLink가 여러 활동 기록을 근거가 연결된 하나의 업무 맥락으로 변환합니다.

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

## AI 모드

`demo` profile의 기본값은 외부 과금 없이 재현 가능한 **fake 요약 모드**다. OpenAI 모드는 아래처럼 명시적으로만 켠다. 값은 셸 기록이나 문서에 실제 비밀값을 적지 않고 실행 환경에서 주입한다.

```text
KL_DEMO_AI_PROVIDER=openai
OPENAI_API_KEY=<secret>
OPENAI_MODEL=<model-id>
```

같은 설정의 Spring property는 각각 `kl.demo.ai.provider`, `kl.demo.ai.openai.api-key`, `kl.demo.ai.openai.model`이다. 특정 모델명이나 비밀값은 저장소에 고정하지 않는다. API 키는 파일, Git 기록, 로그, 브라우저 번들 또는 발표 화면에 넣지 않는다.

Amazon Bedrock 모드는 AWS SDK 기본 자격 증명 체인을 사용한다. 로컬에서는 AWS 프로필을 사용할 수 있고, EC2에서는 액세스 키를 저장하지 않고 인스턴스 IAM Role에 선택한 모델의 호출 권한만 부여한다.

```text
KL_DEMO_AI_PROVIDER=bedrock
AWS_REGION=<Bedrock 모델을 사용할 리전>
BEDROCK_MODEL_ID=<해당 리전에서 접근 가능한 모델 또는 inference profile ID>
```

Bedrock의 모델 접근과 Converse API 지원 여부는 계정·리전에서 발표 전에 확인한다. 실제 키, 세션 토큰, 모델 ID는 저장소에 커밋하지 않는다. 공급자 호출이 실패해도 서버가 자동으로 유료 호출을 반복하거나 조용히 fake 결과로 바꾸지 않는다. 발표 중 네트워크 장애에 대비한 전환은 운영자가 `KL_DEMO_AI_PROVIDER=fake`로 명시적으로 수행한다.

성공한 요약은 같은 대상·같은 활동 목록에 대해 프로세스 메모리에 보관하므로, 같은 버튼을 반복해 눌러도 공급자를 다시 호출하지 않는다. 캐시는 재시작하면 비워지고 실패는 보관하지 않는다. 공급자 실패나 허용되지 않은 근거 ID는 `503 TEMPORARY_UNAVAILABLE`로 응답하며, 원인은 서버 로그에만 남긴다. 심사 전에 세 종류 요약을 한 번씩 눌러 두면 이후 응답은 즉시 나온다.

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
```

EC2에는 장기 AWS 액세스 키를 저장하지 않는다. 인스턴스 IAM Role에 선택한 Bedrock 모델 호출 권한만 부여한다. 배포 직후 `/actuator/health`, `/demo/`, 활동 API, 세 종류의 요약 API를 순서대로 확인한다.

## 발표 전 점검

- `/demo/`가 외부 CDN이나 프론트엔드 빌드 없이 열린다.
- 활동 목록이 최신순으로 보이고 원본 근거 링크가 새 창에서 열린다.
- 팀원·프로젝트·인수인계 요약의 네 섹션이 렌더링된다.
- 각 요약 문장에 근거가 표시된다.
- 서버를 끈 상태에서는 로딩 후 안전한 오류 문구가 표시된다.
- 키보드만으로 선택, 버튼 실행, 원본 링크 이동이 가능하다.
- 실제 조직명, 실제 사용자명, 비밀값이 화면이나 응답에 포함되지 않는다.

데모에서 외부 AI 호출을 사용한다면 발표 전에 응답 시간과 계정 사용 한도를 별도로 확인한다. fake 모드는 네트워크나 공급자 상태와 무관한 발표용 기본 경로다.
OpenAI 모드는 별도 예산·인증·요청 제한을 붙이지 않은 로컬 생존 테스트용이므로 공개 서버에 그대로 노출하지 않는다.
