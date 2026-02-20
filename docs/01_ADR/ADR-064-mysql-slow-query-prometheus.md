# ADR-064: MySQL Slow Query Log와 Prometheus 통합 Observability

## Status
**Accepted** (2026-02-20)

## Context

### 1장: 문제의 발견 (Problem)

#### 1.1 DB 성능 병목의 보이지 않는 적

**PR #213**과 **Issue #209**에서 **MySQL Slow Query를 실시간으로 모니터링하지 못해** 성능 저하 원인을 파악하는데 수시간이 소요되는 문제가 발견되었습니다.

**사건 발생 (2026-02-15)**:
```
증상: API 응답 시간 P99 > 5000ms
원인 파악 시간: 4시간
귀결: 특정 N+1 쿼리가 500ms+ 소요
```

**문제의 본질**:
1. **Slow Query Log를 수동으로 확인**: `SHOW ENGINE INNODB STATUS`로 직접 접속 필요
2. **실시간 알림 부재**: Slow Query가 발생해도 즉시 알 수 없음
3. **추적 어려움**: 어떤 API 호출이 Slow Query를 유발했는지 연결 불가

#### 1.2 기존 모니터링의 한계

**ADR-053 (Observability Stack)**으로 Prometheus + Grafana + Loki를 도입했지만, **DB 성능 모니터링은 누락**되었습니다:

| 계층 | 모니터링 여부 | 도구 |
|------|--------------|------|
| Application | ✅ | Micrometer, Prometheus |
| Redis | ✅ | Redisson Metrics |
| **MySQL** | **❌** | 수동 로그 확인만 가능 |
| External API | ✅ | WebClient Metrics |

#### 1.3 Slow Query의 영향

**Issue #208**에서 분석된 주요 Slow Query:
```sql
-- 1. N+1 Query (500ms+)
SELECT * FROM equipment WHERE character_id = ?;
-- 루프 내에서 100회 호출

-- 2. JOIN 없는 Fetch 전략 (800ms+)
SELECT * FROM cube_history WHERE user_id = ?;
-- 10,000건 전체 스캔

-- 3. 인덱스 미사용 (1200ms+)
SELECT * FROM donation_log WHERE created_at BETWEEN ? AND ?;
-- created_at 인덱스 없음
```

---

### 2장: 선택지 탐색 (Options)

#### 2.1 선택지 1: MySQL Slow Query Log 수동 확인

**방식**: `mysqld.log` 파일을 주기적으로 SSH 접속하여 확인

```bash
# SSH 접속 후
tail -f /var/log/mysql/slow-query.log
```

**장점**:
- 추가 도구 불필요

**단점**:
- **실시간 알림 불가**: 문제가 발생한 후에야 확인 가능
- **수동 작업**: DevOps 엔지니어가 주기적으로 확인해야 함
- **추적 불가**: 어떤 API 호출이 Slow Query를 유발했는지 알 수 없음

**결론**: **능동적 모니터링이 아님**

---

#### 2.2 선택지 2: APM 도구 도입 (Datadog, New Relic)

**방식**: 상용 APM 도구로 DB 성능 모니터링

**장점**:
- **완벽한 추적**: SQL → API Endpoint 연결
- **자동 알림**: 임계값 초과 시 즉시 알림

**단점**:
- **비용**: Datadog Core $15/host/월, Pro $23/host/월
- **과잉 기능**: DB 모니터링만 필요한데 전체 APM 기능 구매

**결론**: **비용 대비 효율 낮음**

---

#### 2.3 선택지 3: Percona Slow Query Log Exporter + Prometheus (선택)

**방식**:
1. **MySQL Slow Query Log** 활성화 (long_query_time = 1초)
2. **Percona Slow Query Log Exporter**로 Prometheus Metrics 변환
3. **Grafana Dashboard**로 실시간 모니터링
4. **AlertManager**로 Slack/Discord 알림

**구성도**:
```
MySQL
  ↓ Slow Query Log
Percona Exporter (textfile collector)
  ↓ metrics
Prometheus (scrape every 15s)
  ↓ query
Grafana Dashboard
  ↓ alert
AlertManager → Discord
```

**장점**:
- **무료**: 오픈소스 도구만 사용
- **실시간**: 15초 간격 Scraping
- **통합**: 기존 Prometheus/Grafana와 통합 (ADR-053)
- **상세 분석**: Query Digest로 쿼리 패턴 분석 가능

**단점**:
- **설치 복잡도**: Exporter를 별도로 설치해야 함

**결론**: **가장 비용 효율적이고 통합적인 해결책**

---

### 3장: 결정의 근거 (Decision)

#### 3.1 선택: Percona Slow Query Log Exporter + Prometheus

MapleExpectation 프로젝트는 **선택지 3: Percona Slow Query Log Exporter + Prometheus**를 채택했습니다.

**결정 근거**:
1. **ADR-053 (Observability Stack)과의 통합**: 이미 Prometheus + Grafana가 있음
2. **비용 효율**: 상용 APM 대비 월 $100+ 절감
3. **오픈소스 생태계**: Percona는 MySQL 모니터링 표준 도구

---

### 4장: 구현의 여정 (Action)

#### 4.1 MySQL Slow Query Log 활성화

**파일**: `my.cnf`

```ini
[mysqld]
# Slow Query Log 활성화
slow_query_log = 1
slow_query_log_file = /var/log/mysql/slow-query.log
long_query_time = 1  # 1초 이상인 쿼리를 Slow로 간주
log_queries_not_using_indexes = 1  # 인덱스 미사용 쿼리도 기록
```

#### 4.2 Percona Slow Query Log Exporter 설치

**Docker Compose**:
```yaml
services:
  percona-exporter:
    image: percona/mysqld-exporter:latest
    command:
      - --collect.auto_generate.columns=true
      - --collect.slave_status=true
      - --collect.slave_hosts=true
      - --collect.info_schema.processlist.processes_by_user=true
      - --collect.info_schema.processlist.processes_by_host=true
      - --collect.heartbeat-table=true
      - --collect.heartbeat.database=heartbeat
    environment:
      - DATA_SOURCE_NAME=root:password@(mysql:3306)/
    ports:
      - "9104:9104"
    volumes:
      - /var/log/mysql:/var/log/mysql:ro
```

#### 4.3 Prometheus Configuration

**파일**: `prometheus.yml`

```yaml
scrape_configs:
  - job_name: 'mysql-slow-query'
    static_configs:
      - targets: ['percona-exporter:9104']
    scrape_interval: 15s
```

#### 4.4 Grafana Dashboard

**Import ID**: `7362` (MySQL Slow Query Summary)

**주요 Panel**:
```
1. Slow Query Count (1m)
2. Slow Query Execution Time (P99)
3. Top 10 Slow Queries
4. Slow Query by Database
5. Slow Query Trend (24h)
```

#### 4.5 Alert Rule

**파일**: `prometheus/alerts.yml`

```yaml
groups:
  - name: mysql_slow_query
    rules:
      - alert: MySQLSlowQueryHigh
        expr: rate(mysql_global_status_slow_queries[5m]) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "MySQL Slow Query Rate High"
          description: "Slow Query rate: {{ $value }} queries/sec"

      - alert: MySQLSlowQueryCritical
        expr: histogram_quantile(0.99, rate(mysql_slow_query_duration_seconds_bucket[5m])) > 5
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "MySQL Slow Query P99 > 5s"
          description: "P99 Slow Query duration: {{ $value }}s"
```

#### 4.6 애플리케이션 MDC와 연동

**파일**: `maple/expectation/config/LoggingConfig.java`

```java
package maple.expectation.config;

import org.slf4j.MDC;
import jakarta.servlet.*;
import java.util.UUID;

public class RequestIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);

        // SQL Comment로 requestId 포함
        try (Connection conn = dataSource.getConnection()) {
            Statement stmt = conn.createStatement();
            stmt.execute("SET @request_id = '" + requestId + "'");
        }

        chain.doFilter(req, res);
        MDC.clear();
    }
}
```

**Slow Query Log에 requestId 포함**:
```sql
/* requestId=abc-123-def-456 */ SELECT * FROM equipment WHERE character_id = ?;
# Query_time: 0.5123  Lock_time: 0.0001 Rows_sent: 100  Rows_examined: 100000
```

---

### 5장: 결과와 학습 (Result)

#### 5.1 성과

1. **실시간 Slow Query 탐지**: 15초 간격으로 Slow Query 발생 즉시 확인
2. **자동 알림**: P99 > 5초 시 Discord로 즉시 알림
3. **상세 분석**: 쿼리 패턴별 집계로 병목 지점 명확히 파악

#### 5.2 학습한 점

1. **DB 성능은 보이지 않는 병목**: 애플리케이션 코드가 빨라도 DB가 느리면 전체가 느림
2. **Slow Query는 진단 도구**: 단순히 "느리다"가 아니라 "어디서 왜 느린지"를 알려줌
3. **Prometheus 통합의 편리성**: 별도 APM 없이도 충분한 DB 모니터링 가능

#### 5.3 향후 개선 방향

- **Query Auto-Tuning**: Slow Query를 자동으로 인덱스 제안으로 변환
- **Explain Plan 자동 수집**: Slow Query 발생 시 EXPLAIN 결과를 Loki에 저장

---

## Consequences

### 긍정적 영향
- **성능 병목 조기 발견**: Slow Query를 실시간으로 탐지
- **데이터 기반 최적화**: 어떤 쿼리를 먼저 최적화할지 우선순위 명확

### 부정적 영향
- **로그 스토리지**: Slow Query Log 파일이 디스크를 소비
- **MySQL 부하**: Slow Query Log 기록 자체가 오버헤드

### 위험 완화
- **로그 로테이션**: Logrotate로 일/주 단위 로그 관리
- **비프로덕션 환경에서는 비활성화**: 개발 환경에서는 long_query_time = 10으로 완화

---

## References

- **PR #213**: feat(#209): MySQL Slow Query Log + Prometheus 통합 Observability
- **Issue #209**: [Observability] MySQL Slow Query Log 활성화 및 Loki+Grafana 연동을 통한 쿼리 성능 모니터링
- **Issue #208**: [Performance] DB 성능 최적화를 위한 InnoDB Buffer Pool 튜닝 및 커버링 인덱스 추가
- **ADR-053**: Observability Stack (Prometheus + Grafana + Loki + OpenTelemetry)
- **Percona Monitoring and Management (PMM)**: https://www.percona.com/software/database-tools/percona-monitoring-and-management
