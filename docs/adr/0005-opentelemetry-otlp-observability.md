# ADR 0005. 관측 데이터는 OpenTelemetry(OTLP)로 내보내고 Prometheus·Tempo·Grafana로 본다

- 상태: 채택
- 날짜: 2026-09-16
- 관련: [ADR 0004](0004-rds-postgresql-for-production.md)(운영 DB RDS), [명세 01](../knowledge-link/01-functional-spec.md) 후속 범위

## 맥락

작업 엔진이 생기면서 봐야 할 것이 생겼다. 작업이 얼마나 기다리는지, 얼마나 걸리는지, lease 만료와 재시도가 얼마나 나는지, 슬롯이 얼마나 차 있는지다. 동기화·AI 호출이 붙으면 요청 하나가 여러 작업과 외부 호출로 이어지므로 트레이스도 필요하다.

명세는 Prometheus를 "측정 근거가 생긴 뒤 결정"하는 후속 범위로 두었다. 그런데 측정 수단이 없으면 그 근거도 만들 수 없다. 운영은 메모리 2GB 앱 서버와 RDS라서, 서버에 관측 컨테이너를 여러 개 올리기 어렵다.

## 결정

- 앱은 Micrometer로 지표를, Micrometer Tracing(OpenTelemetry 브리지)으로 트레이스를 만들고, 둘 다 OTLP/HTTP로 **내보낸다**(push). Spring Boot 4의 `spring-boot-starter-opentelemetry`를 쓴다.
- 앱은 지표용 HTTP 엔드포인트(`/actuator/prometheus`)를 열지 않는다. 공개 actuator는 지금처럼 health뿐이다.
- 내보내기는 기본으로 끄고 `otel` 프로필에서 켠다. 수집기 주소는 `KL_OTLP_ENDPOINT` 하나로 정한다.
- 로컬은 `grafana/otel-lgtm` 컨테이너 하나(OpenTelemetry Collector·Prometheus·Tempo·Grafana)를 compose의 `observability` 프로필로 띄운다.
- 운영은 같은 OTLP 설정으로 외부 수집기에 보낸다. 후보는 Grafana Cloud 무료 티어이며, 배포 전에 한도와 보존 기간을 확인해 정한다.
- 작업 엔진 지표는 `kl.jobs.*`로 만든다. 태그는 작업 종류·결과·상태·슬롯처럼 값이 정해진 것만 쓴다.

| 지표 | 종류 | 태그 | 뜻 |
| --- | --- | --- | --- |
| `kl.jobs.claimed` | counter | kind | 선점한 작업 수 |
| `kl.jobs.queue.wait` | timer | kind | 실행 가능해진 뒤 선점까지 걸린 시간(슬롯 대기 포함) |
| `kl.jobs.execution` | timer | kind, outcome | handler 실행부터 상태 전환까지. outcome은 succeeded·retry·failed·lease_lost·error |
| `kl.jobs.recovered` | counter | | lease 만료로 복구한 작업 수 |
| `kl.jobs.count` | gauge | status | 상태별 작업 수(15초마다 갱신) |
| `kl.jobs.slot.busy` | gauge | slot | 슬롯을 쓰고 있으면 1 |

- 트레이스 샘플링은 데모 규모라 100%로 둔다. 트래픽이 늘면 낮춘다.

## 이유

- 수집기 주소 하나만 바꾸면 로컬과 운영이 전환된다. 앱 서버에 Prometheus·Tempo·Grafana를 따로 띄우지 않아도 된다.
- 지표 엔드포인트를 열지 않으니, 그 엔드포인트를 외부에서 못 보게 막는 보안 설정이 필요 없다.
- OTLP는 공급자 중립 표준이라 수집기를 바꿔도 앱 코드는 그대로다.
- Boot 4 스타터가 트레이싱 브리지와 OTLP 내보내기를 함께 구성한다. Boot 4에서는 트레이스 주소 속성이 `management.opentelemetry.tracing.export.otlp.endpoint`로 바뀌었고, 3.x의 `management.otlp.tracing.endpoint`는 오류 수준으로 폐기되었다.

## 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| Prometheus가 `/actuator/prometheus`를 긁어 감(pull) | 지표 엔드포인트를 외부에서 못 보게 막아야 하고, 운영에서 긁어 갈 Prometheus를 어딘가에 띄워야 한다 |
| 앱 서버에 Prometheus·Tempo·Grafana를 직접 운영 | 메모리 2GB 서버에서 부담이 크다 |
| CloudWatch만 사용 | RDS 지표는 볼 수 있지만, 애플리케이션 트레이스와 사용자 정의 지표에는 도구와 비용이 따로 든다. RDS 지표를 보는 보조 수단으로는 쓴다 |

## 결과

- 로컬에서 관측 스택을 켜면 메모리를 더 쓴다. 필요할 때만 켠다.
- 로그는 아직 OTLP로 내보내지 않는다. 콘솔 로그를 유지한다.
- RDS 지표는 앱이 내보내지 않는다. CloudWatch나 원격 postgres_exporter로 본다(ADR 0004).
- 운영 수집기의 인증 값은 환경 변수(`MANAGEMENT_OTLP_METRICS_EXPORT_HEADERS_AUTHORIZATION`, `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_HEADERS_AUTHORIZATION`)로만 넣는다.
- 명세의 후속 범위에서 Prometheus를 뺀다. Redis 등 나머지 인프라 확장은 그대로 측정 근거가 생긴 뒤 정한다.
