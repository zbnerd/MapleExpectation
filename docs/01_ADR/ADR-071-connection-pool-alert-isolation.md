# ADR-071: Connection Pool 고갈 시 Discord 웹훅 알림 격리

## Status
**Accepted** (2026-02-20)

## Context

### 1장: 문제의 발견 (Problem)

#### 1.1 장애 상황에서 알림 미작동 심각성

**Issue #345**에서 **Connection Pool 고갈 시 Discord 웹훅 알림이 전송되지 않는** 치명적 문제가 발견되었습니다.

**사건 발생 (2026-02-11 13:20 KST)**:
1. 스케줄러 스레드 무한 생성 (Issue #344)
2. MySQL Connection Pool 고갈 (total=59, active=59, idle=0)
3. **알림 서비스가 DB 의존하므로 Discord 웹훅 전송 실패**
4. 운영자가 장애를 인지하지 못함

**근본 원인**:
```java
// Anti-Pattern: 알림 서비스가 DB 의존
@Service
@RequiredArgsConstructor
public class DiscordAlertService {

    private final AlertLogRepository alertLogRepository;  // DB 의존

    public void sendAlert(String message) {
        // 1. DB에 알림 로그 저장 시도 → Connection Pool 고갈로 BLOCK
        alertLogRepository.save(new AlertLog(message));

        // 2. Discord 웹훅 전송 ← 도달하지 못함
        discordWebhook.send(message);
    }
}
```

#### 1.2 Alert Service의 의존성 분석

**문제 지점**:
| 의존성 | 용도 | 장애 시 영향 |
|--------|------|-------------|
| MySQL | 알림 로그 저장 | Connection Pool 고갈 시 BLOCK |
| Redis | 알림 중복 방지 | Redis 장애 시 BLOCK |
| WebClient | Discord API 호출 | 영향 없음 (순수 HTTP) |

#### 1.3 Outbox 패턴의 함정

Transactional Outbox (ADR-046)를 사용할 경우 **Outbox 저장 시에도 DB Connection이 필요**하므로 Connection Pool 고갈 시 알림 생성 자체가 불가능합니다.

**기존 Outbox Flow**:
```
1. 비즈니스 로직 실행
2. @Transactional 내에서 Outbox 저장 → DB Connection 필요
3. OutboxProcessor가 비동기로 알림 전송
```

**문제**: 단계 2에서 Connection Pool 고갈 시 BLOCK.

---

### 2장: 선택지 탐색 (Options)

#### 2.1 선택지 1: 알림 전용 Connection Pool 분리

**방식**: Spring Multi-Datasource로 알림 서비스용 별도 HikariCP Pool 생성

```yaml
spring:
  datasource:
    alert:
      jdbc-url: jdbc:mysql://localhost:3306/maple
      hikari:
        maximum-pool-size: 5  # 알림 전용
        connection-timeout: 2000
```

**장점**:
- 메인 Connection Pool 고갈 시에도 알림 전송 가능

**단점**:
- **근본적 해결 아님**: 알림 전용 Pool도 고갈 가능
- **리소스 낭비**: 평소 연결이 유휴 상태
- **복잡성 증가**: Multi-Datasource 설정 관리

**결론**: **임시 방편일 뿐 근본 해결책 아님**

---

#### 2.2 선택지 2: 알림 큐/버퍼를 DB 외부에 두기 (SQS/Kafka)

**방식**: AWS SQS나 Kafka를 메시지 큐로 사용

```java
@Service
public class DiscordAlertService {

    private final SqsClient sqsClient;

    public void sendAlert(String message) {
        // SQS는 DB Connection 의존하지 않음
        sqsClient.sendMessage(sqseUrl, message);
    }
}
```

**장점**:
- **완전한 격리**: DB/Redis 장애 시에도 메시지 전송
- **확장성**: 메시지 큐는 장애 격리에 최적화

**단점**:
- **운영 복잡도**: AWS/인프라 관리 필요
- **비용**: SQS/Kafka 운영 비용 발생
- **과잉 설계**: Discord 웹훅 정도의 알림에 SQS는 과함

**결론**: **장기적으로는 고려 가능하나, 단기 대응으로는 과잉**

---

#### 2.3 선택지 3: 알림 전송을 무상태(Stateless) HTTP 호출로 격리 (선택)

**방식**:
1. Discord 웹훅 전송은 **순수 HTTP 호출**로 구현
2. DB/Redis 의존 제거
3. 알림 로그는 **나중에 비동기로 저장** (Best Effort)

```java
@Service
public class DiscordAlertService {

    private final WebClient webClient;
    private final AlertLogRepository alertLogRepository;  // 비동기 전용

    public void sendAlert(String message) {
        // 1. 동기: Discord 웹훅 전송 (DB/Redis 의존 없음)
        try {
            webClient.post()
                .uri(webhookUrl)
                .bodyValue(message)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(3));
        } catch (Exception e) {
            log.error("Discord 웹훅 전송 실패", e);
            // 알림 실패해도 비즈니스 로직에는 영향 없음
        }

        // 2. 비동기: 알림 로그 저장 (실패해도 무방)
        CompletableFuture.runAsync(() -> {
            try {
                alertLogRepository.save(new AlertLog(message));
            } catch (Exception e) {
                log.warn("알림 로그 저장 실패 (무시)", e);
            }
        });
    }
}
```

**장점**:
- **완전한 격리**: Discord 전송은 DB/Redis에 전혀 의존하지 않음
- **단순함**: 인프라 변경 불필요
- **실패해도 안전**: 알림 로그 저장 실패가 알림 전송을 방해하지 않음

**단점**:
- **알림 로그 유실 가능성**: 애플리케이션 크래시 시 로그 소실
- **중복 알림 가능성**: 중복 방지 로직이 없으면 동일 장애에 대해 여러 번 알림

**결론**: **현재 상황에 가장 적합한 균형적 해결책**

---

### 3장: 결정의 근거 (Decision)

#### 3.1 선택: 알림 전송 무상태(Stateless) HTTP 호출 격리

MapleExpectation 프로젝트는 **선택지 3: 알림 전송을 무상태 HTTP 호출로 격리**를 채택했습니다.

**결정 근거**:
1. **장애 상황에서도 알림 필수**: Connection Pool 고갈 시에도 Discord 알림은 반드시 전송되어야 함
2. **단순함 우선**: 인프라 변경(SQS/Kafka) 없이 코드만으로 해결 가능
3. **Best Effort 로깅**: 알림 로그는 "장애 원인 분석용"이므로 유실되어도 치명적이지 않음

---

### 4장: 구현의 여정 (Action)

#### 4.1 DiscordAlertService 재구현

**파일**: `maple/expectation/alert/DiscordAlertService.java`

```java
package maple.expectation.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordAlertService {

    private final WebClient webClient;
    private final AlertLogRepository alertLogRepository;  // 비동기 전용

    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(3);
    private static final String DISCORD_WEBHOOK_URL = System.getenv("DISCORD_WEBHOOK_URL");

    /**
     * 장애 발생 시 Discord 웹훅 알림 전송
     *
     * 중요: 이 메서드는 DB/Redis에 의존하지 않아야 함
     */
    public void sendAlert(String title, String message, String severity) {
        String discordMessage = buildDiscordMessage(title, message, severity);

        // 1. 동기: Discord 웹훅 전송 (DB/Redis 의존 없음)
        try {
            webClient.post()
                .uri(DISCORD_WEBHOOK_URL)
                .bodyValue(discordMessage)
                .retrieve()
                .bodyToMono(String.class)
                .block(WEBHOOK_TIMEOUT);

            log.info("Discord 알림 전송 성공: {}", title);
        } catch (Exception e) {
            log.error("Discord 웹훅 전송 실패 (무시)", e);
            // 알림 실패해도 비즈니스 로직에는 영향 없음
        }

        // 2. 비동기: 알림 로그 저장 (Best Effort)
        saveAlertLogAsync(title, message, severity);
    }

    /**
     * 알림 로그 비동기 저장
     * 실패해도 Discord 알림은 이미 전송되었으므로 무방
     */
    private void saveAlertLogAsync(String title, String message, String severity) {
        CompletableFuture.runAsync(() -> {
            try {
                AlertLog log = new AlertLog(title, message, severity);
                alertLogRepository.save(log);
            } catch (Exception e) {
                log.warn("알림 로그 저장 실패 (무시): title={}", title, e);
            }
        });
    }

    private String buildDiscordMessage(String title, String message, String severity) {
        String color = switch (severity) {
            case "P0", "CRITICAL" -> "16711680";  // Red
            case "P1", "HIGH" -> "16776960";      // Orange
            default -> "65280";                  // Green
        };

        return String.format("""
            {
              "embeds": [{
                "title": "%s",
                "description": "%s",
                "color": %s,
                "timestamp": "%s"
              }]
            }
            """, title, message, color, java.time.Instant.now());
    }
}
```

#### 4.2 중복 알림 방지

**파일**: `maple/expectation/alert/AlertDeduplicationCache.java`

```java
package maple.expectation.alert;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AlertDeduplicationCache {

    private final StringRedisTemplate redisTemplate;

    /**
     * 동일한 장애에 대해 5분内 중복 알림 방지
     */
    public boolean shouldAlert(String alertKey) {
        String key = "alert:dedup:" + alertKey;
        Boolean isFirstTime = redisTemplate.opsForValue()
            .setIfAbsent(key, "1", Duration.ofMinutes(5));

        return Boolean.TRUE.equals(isFirstTime);
    }
}
```

#### 4.3 Connection Pool 모니터링

**파일**: `maple/expectation/monitoring/ConnectionPoolMonitor.java`

```java
package maple.expectation.monitoring;

import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionPoolMonitor {

    private final DataSource dataSource;
    private final DiscordAlertService discordAlertService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @PostConstruct
    public void startMonitoring() {
        scheduler.scheduleAtFixedRate(this::checkPoolHealth, 0, 30, TimeUnit.SECONDS);
    }

    private void checkPoolHealth() {
        try {
            HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class)
                .getHikariPoolMXBean();

            int activeConnections = pool.getActiveConnections();
            int totalConnections = pool.getTotalConnections();
            int maxPoolSize = 30;  // 설정값

            double usageRatio = (double) activeConnections / maxPoolSize;

            if (usageRatio >= 0.9) {
                String alertKey = "connection-pool-high";
                if (discordAlertService.shouldAlert(alertKey)) {
                    discordAlertService.sendAlert(
                        "Connection Pool 고갈 경고",
                        String.format("사용 중: %d/%d (%.1f%%)",
                            activeConnections, maxPoolSize, usageRatio * 100),
                        "P0"
                    );
                }
            }
        } catch (Exception e) {
            log.error("Connection Pool 모니터링 실패", e);
        }
    }
}
```

---

### 5장: 결과와 학습 (Result)

#### 5.1 성과

1. **Connection Pool 고갈 시에도 알림 전송**: Discord 웹훅이 DB/Redis에 의존하지 않음
2. **중복 알림 방지**: Redis Deduplication Cache로 5분内 동일 장애에 대한 중복 알림 방지
3. **프로액티브 모니터링**: Connection Pool 사용량 90% 초과 시 사전 경고

#### 5.2 학습한 점

1. **알림 시스템은 최후의 보루**: 장애 상황에서도 반드시 작동해야 함
2. **DB 의존 제거的重要性**: Connection Pool 고갈은 전체 시스템 장애이므로, 알림은 격리되어야 함
3. **Best Effort 로깅**: 알림 로그는 유실되어도 Discord 전송이 성공하면 충분

#### 5.3 향후 개선 방향

- **SQS/Kafka 도입**: 장기적으로는 메시지 큐로 격리 강화
- **알림 우선순위 큐**: P0 알림은 즉시, P2는 배치 전송
- **알림 채널 다중화**: Discord + Email + SMS

---

## Consequences

### 긍정적 영향
- **장애 감지 시간 단축**: Connection Pool 고갈 시 즉시 Discord 알림
- **운영 안정성**: 운영자가 장애를 신속히 인지하고 대응 가능

### 부정적 영향
- **알림 로그 유실 가능성**: 애플리케이션 크래시 시 로그 소실
- **중복 알림 가능성**: Deduplication Cache TTL(5분) 만료 후 재발 시 재알림

### 위험 완화
- **Discord 웹훅 전송 성공 로그**: Discord 서버 로그를 "진실의 원천(Trust Source)"으로 활용
- **장기 로그 저장**: Discord 메시지를 주기적으로 백업

---

## References

- **Issue #345**: feat: 커넥션 풀 고갈 시 Discord 웹훅 알림 미작동 문제
- **Issue #344**: P0: 운영환경 MySQL Connection Pool 고갈로 인한 서비스 불안정
- **ADR-046**: Transactional Outbox 패턴과 Triple Safety Net
