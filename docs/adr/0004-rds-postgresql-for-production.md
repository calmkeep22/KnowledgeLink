# ADR 0004. 운영 DB를 Amazon RDS for PostgreSQL로 둔다

- 상태: 채택
- 날짜: 2026-09-15
- 관련: [ADR 0001](0001-postgresql-pgvector-single-database.md)의 "PostgreSQL 하나에 모두 둔다"는 그대로이고, 운영 DB를 어디서 돌리는지만 바꾼다.

## 맥락

기존 명세는 서버 1대에서 Nginx·Spring Boot·PostgreSQL을 docker compose로 실행하고, RDS는 쓰지 않으며, 하루 1회 pg_dump를 S3에 올려 백업하는 방식이었다. 비용은 가장 적지만 백업·복원·패치를 직접 운영해야 하고, 메모리 2GB 서버에서 JVM과 DB가 메모리를 나눠 쓴다. 서버가 망가지면 DB도 함께 잃는다.

## 결정

운영 DB는 Amazon RDS for PostgreSQL 17, Single-AZ로 둔다. 앱(Nginx·Spring Boot)은 서버 1대에서 docker compose로 실행한다. 로컬 개발과 통합 테스트는 지금처럼 `pgvector/pgvector:pg17` 이미지를 쓴다.

## 이유

- 자동 백업과 시점 복구(PITR)를 설정만으로 쓸 수 있다. pg_dump 스크립트, S3 수명 주기, 복원 절차를 따로 만들 필요가 없다.
- DB 엔진 패치와 스토리지 관리를 AWS가 맡는다.
- 앱 서버가 망가지거나 교체돼도 데이터가 남고, 앱 서버 메모리는 JVM이 온전히 쓴다.
- 작업 큐·권한 필터·검색이 쓰는 기능(`FOR UPDATE SKIP LOCKED`, 부분 인덱스, 지연 검사 FK, pgvector, pg_trgm)은 RDS PostgreSQL에서도 그대로 쓸 수 있어 코드를 바꾸지 않는다.

## 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 서버 1대 docker compose PostgreSQL(기존 명세) | 비용은 가장 적지만 백업·복원·패치를 직접 운영하고, 앱과 DB가 한 서버의 자원과 장애를 함께 겪는다 |
| RDS Multi-AZ | 장애 조치가 자동이지만 인스턴스 비용이 약 2배다. 데모·심사 기간에만 운영하는 MVP에는 과하다 |
| Aurora PostgreSQL | 작고 간헐적으로 운영하는 규모에서는 RDS보다 최소 비용이 높고, 얻는 이점이 적다 |

## 결과

- 비용: RDS 인스턴스·스토리지·백업 스토리지 비용이 더해진다. 데모 기간이 아니면 인스턴스를 중지한다. RDS는 중지하고 7일이 지나면 자동으로 다시 시작하므로, 휴지 기간이 길면 스냅샷을 남기고 인스턴스를 삭제하는 방법도 검토한다.
- 네트워크: 퍼블릭 접근을 끄고 앱 서버의 보안 그룹에서만 5432 포트를 연다. 앱 서버는 RDS와 같은 VPC에 둔다. Lightsail 인스턴스는 VPC peering을 따로 설정해야 하므로 EC2가 자연스럽다. 서버 상품은 배포 전에 정한다.
- 연결: RDS PostgreSQL 15 이상은 기본으로 SSL을 강제한다(`rds.force_ssl`). `KL_DB_URL`에 `sslmode=verify-full`과 RDS CA 인증서 경로를 넣는다.
- 버전: 엔진은 테스트 이미지와 같은 PostgreSQL 17로 하고, RDS가 지원하는 pgvector 버전을 배포할 때 확인해 테스트 이미지와 맞춘다.
- 확장: V1의 `CREATE EXTENSION vector`, `pg_trgm`에는 `rds_superuser` 권한이 필요하다. Flyway를 마스터 계정이 아닌 계정으로 실행하면 확장을 미리 설치해 둔다.
- 백업: pg_dump→S3 대신 RDS 자동 백업(보존 7일)과 시점 복구를 쓴다. T31의 백업 복원 확인도 스냅샷이나 시점 복구로 새 인스턴스를 만들어 확인한다.
- 재시작·장애: DB 재시작이나 유지 보수로 연결이 끊기면 진행 중인 transaction은 롤백된다. 끊김이 작업 lease(120초)보다 길면 작업은 복구되어 다시 실행되고 시도 1회를 쓴다. 결과는 소유권을 확인한 뒤에만 저장되므로 중복 저장은 없다. Single-AZ라 장애 복구는 스냅샷·시점 복구로 수동으로 한다.
- 관측: Prometheus를 도입하면 postgres_exporter를 앱 서버에 두고 RDS에 원격으로 연결하거나, CloudWatch·Performance Insights를 함께 쓴다.
