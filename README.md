# 개발 지식 연결 서비스

Jira 이슈와 GitHub PR에서 유사한 과거 업무, 관련 코드 변경과 경험이 있는 개발자를 근거와 함께 찾는 단일 조직 MVP다.

## 현재 기준 문서

이슈·PR·커밋 작성과 로컬 커밋 템플릿 설정은 [기여 가이드](CONTRIBUTING.md)를 따른다.

[명세 목차](docs/knowledge-link/00-overview.md)에서 기능·화면·데이터·API·AI 처리·테스트 기준을 확인한다. 현재 구현은 이 명세를 기준으로 한다.
변경 이력은 [CHANGELOG](CHANGELOG.md), 설계 결정은 [ADR](docs/adr), 테스트 결과는 [테스트 결과 기록](docs/testing/test-report.md)에 남긴다.

기술 스택은 Java 21·Spring Boot 4.1, React·TypeScript(예정), PostgreSQL·pgvector·pg_trgm이다. 앱은 AWS 서버 1대에 Docker Compose·Nginx로, DB는 Amazon RDS for PostgreSQL로 배포하며([ADR 0004](docs/adr/0004-rds-postgresql-for-production.md)) 개발은 로컬과 fake AI로 시작한다. 실제 AI 공급자·모델과 서버 상품은 평가 전에 결정한다.

## 로컬 실행

필요: JDK 21, Docker Desktop.

```bash
docker compose up -d
./gradlew bootRun --args="--spring.profiles.active=local"
```

계정은 운영 스크립트로 등록한다. `config/seed.example.yml`을 `config/seed.yml`로 복사하고, 파일에 적은 이름의 환경 변수에 초기 비밀번호(12자 이상)를 넣은 뒤 실행한다. 등록된 계정은 첫 로그인 후 비밀번호를 바꿔야 한다.

```bash
./gradlew bootRun --args="--spring.profiles.active=local,seed --spring.config.import=file:config/seed.yml"
```

## 테스트

```bash
./gradlew test             # 단위 테스트(Docker 불필요)
./gradlew integrationTest  # Testcontainers PostgreSQL 통합 테스트(Docker 필요)
./gradlew benchmark        # 성능 비교(수동 실행, 약 10분). 기록은 docs/benchmarks
```

## 구현 및 검증 상태

| 단계 | 범위 | 상태 |
| --- | --- | --- |
| 1 | 프로젝트 기반, 인증(F01), 공통 오류, 계정 seed | 구현 완료. 2026-09-12 기준 단위 테스트 33건, 통합 테스트 19건(T01 포함) 통과 |
| 2 | scope·grant 접근 모델, `GET /scopes` | 구현 완료. 2026-09-15 기준 단위 테스트 63건, 통합 테스트 35건 통과 |
| 3 | 작업 엔진(job·실행 슬롯·lease) | 진행 중(`feat/job-engine`). 엔진·실행기 구현, 2026-09-15 기준 단위 테스트 75건, 통합 테스트 57건 통과(T24·T32 PASS, T25 일부). 작업 handler와 `/jobs` API는 아직 없음 |
| 4~9 | 커넥터·색인·검색·AI·프론트엔드·배포 | 예정 |

- 명세의 API·AI 계약과 수용 테스트 중 위 표에 없는 항목은 아직 구현·실행되지 않았다.
- 로그인 시도 제한은 아직 없다. 공개 배포 전에 추가한다.
