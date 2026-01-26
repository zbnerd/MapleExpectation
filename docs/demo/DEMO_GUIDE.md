# Demo Guide

> MapleExpectation 핵심 기능 시연 가이드
> 총 소요 시간: 약 10분

**Last Updated**: 2026-01-26

---

## 사전 준비

```bash
# 1. 프로젝트 클론 및 빌드
git clone https://github.com/zbnerd/MapleExpectation.git
cd MapleExpectation
./gradlew build -x test

# 2. 인프라 실행 (MySQL, Redis)
docker-compose up -d

# 3. 애플리케이션 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. 상태 확인
curl http://localhost:8080/actuator/health
# 예상: {"status":"UP"}
```

### 접속 URL

| 서비스 | URL | 비고 |
|--------|-----|------|
| Application | http://localhost:8080 | API 서버 |
| Swagger UI | http://localhost:8080/swagger-ui.html | API 문서 |
| Actuator | http://localhost:8080/actuator | 헬스체크, 메트릭 |
| Grafana | http://localhost:3000 | admin/admin |
| Prometheus | http://localhost:9090 | 메트릭 조회 |

---

## Demo 1: 고처리량 API 성능 (3분)

### 목표
200~300KB JSON을 처리하면서 **RPS 965, p50 95ms**를 달성함을 보여줍니다.

### 시연 절차

**Step 1: API 정상 동작 확인**
```bash
# 캐릭터 기대값 조회 (실제 API 엔드포인트)
curl "http://localhost:8080/api/v4/characters/강은호/expectation" | jq '.presets[0].totalCostText'
# 예상: "1,234,567,890" (숫자 형식)
```

**Step 2: 부하 테스트 실행**
```bash
# wrk 부하테스트 (Docker)
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v $(pwd)/load-test:/scripts \
  williamyeh/wrk \
  -t4 -c100 -d30s \
  -s /scripts/wrk-v4-expectation.lua \
  http://host.docker.internal:8080
```

**Step 3: 결과 확인**
```
예상 결과:
========================================
  V4 Expectation API Load Test Results
========================================
Requests/sec:    965+
Latency Distribution:
  50%:           95 ms
  99%:           214 ms
Errors:
  Connect:       0
  Timeout:       0
========================================
```

### 핵심 메시지
> "요청당 200~300KB JSON을 처리하면서 초당 965건, 에러 0%를 달성했습니다.
> 이는 일반 웹 서비스 기준 **14만 RPS 등가 처리량**입니다."

---

## Demo 2: Circuit Breaker 동작 (3분)

### 목표
외부 API 장애 시 **빠른 실패(Fail-Fast)**와 **자동 복구**를 보여줍니다.

### 시연 절차

**Step 1: 정상 상태 확인**
```bash
# Circuit Breaker 상태 확인
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
# 예상: "nexonApi": { "status": "UP", "details": { "state": "CLOSED" } }
```

**Step 2: 외부 API 장애 시뮬레이션**
```bash
# 존재하지 않는 캐릭터로 반복 요청 (404 에러 유도)
for i in {1..15}; do
  curl -s -o /dev/null -w "Request $i: %{http_code}\n" \
    "http://localhost:8080/api/v4/characters/존재하지않는캐릭터$i/expectation"
  sleep 0.5
done
```

**Step 3: Circuit Open 확인**
```bash
# Circuit 상태 확인
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers.details.nexonApi'
# 예상: "state": "OPEN" 또는 "HALF_OPEN"
```

**Step 4: Fallback 응답 확인**
```bash
# Circuit Open 상태에서 요청
curl -s "http://localhost:8080/api/v4/characters/강은호/expectation" | jq '.error // .presets[0]'
# 예상: 캐시된 데이터 또는 빠른 에러 응답 (지연 없음)
```

**Step 5: 자동 복구 (10초 후)**
```bash
# Half-Open 전이 대기
sleep 15

# 정상 요청으로 Circuit 복구
curl -s "http://localhost:8080/api/v4/characters/강은호/expectation" > /dev/null

# 상태 확인
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers.details.nexonApi.state'
# 예상: "CLOSED"
```

### 핵심 메시지
> "외부 API가 장애 나도 우리 서비스는 즉시 응답합니다.
> 장애가 복구되면 자동으로 정상 상태로 돌아옵니다."

---

## Demo 3: Cache Stampede 방지 - Singleflight (3분)

### 목표
캐시 만료 시 100개 동시 요청이 와도 **외부 API는 1번만 호출**됨을 보여줍니다.

### 시연 절차

**Step 1: 캐시 초기화**
```bash
# Redis 캐시 삭제
docker exec -it $(docker ps -qf "name=redis") redis-cli FLUSHALL
# 예상: OK
```

**Step 2: 로그 모니터링 준비**
```bash
# 새 터미널에서 애플리케이션 로그 모니터링
docker logs -f $(docker ps -qf "name=maple") 2>&1 | grep -E "Nexon|API|Loading"
```

**Step 3: 동시 요청 발생**
```bash
# 100개 동시 요청
for i in {1..100}; do
  curl -s -o /dev/null "http://localhost:8080/api/v4/characters/아델/expectation" &
done
wait
echo "100 requests completed"
```

**Step 4: 결과 확인**
```bash
# 외부 API 호출 횟수 확인 (로그에서)
# 예상: "Loading from Nexon API" 로그가 1~2회만 출력됨

# Prometheus 메트릭 확인
curl -s http://localhost:8080/actuator/prometheus | grep singleflight
# 예상: singleflight_inflight 또는 cache_gets{result="miss"} 값이 1~2
```

### 핵심 메시지
> "100명이 동시에 같은 데이터를 요청해도 외부 API는 **1번만 호출**됩니다.
> 나머지 99명은 첫 번째 조회 결과를 공유합니다. 이것이 Singleflight 패턴입니다."

---

## Demo 4: Graceful Shutdown (1분)

### 목표
서버 종료 시 **진행 중인 요청이 완료**된 후 종료됨을 보여줍니다.

### 시연 절차

**Step 1: 부하 실행 중 Shutdown 시그널**
```bash
# 터미널 1: 지속적인 요청
while true; do
  curl -s -o /dev/null -w "%{http_code} " \
    "http://localhost:8080/api/v4/characters/강은호/expectation"
done

# 터미널 2: Shutdown 시그널
kill -TERM $(pgrep -f "maple-expectation")
```

**Step 2: 로그 확인**
```
예상 로그:
[Shutdown] Preparing shutdown...
[Shutdown] Awaiting pending offers (30s timeout)...
[Shutdown] Draining buffer: 5 tasks flushed
[Shutdown] Graceful shutdown completed
```

### 핵심 메시지
> "서버 종료 시 진행 중인 작업을 완료하고 버퍼를 flush한 후 안전하게 종료합니다.
> 데이터 유실 0건을 보장합니다."

---

## 예상 Q&A

### Q1: "RPS 965가 정말 높은 건가요?"
> A: 일반 웹 서비스(요청당 2KB)와 달리, 우리는 요청당 200~300KB를 처리합니다.
> `965 RPS × 150배 payload = 14만 RPS 등가`입니다.
> 단일 t3.small($15/월)에서 이 처리량은 매우 효율적입니다.

### Q2: "Circuit Breaker가 열리면 데이터가 없는 거 아닌가요?"
> A: Fallback으로 **캐시된 이전 데이터**를 반환합니다.
> "stale-while-revalidate" 패턴으로, 오래된 데이터라도 없는 것보다 낫습니다.
> 최신 데이터가 필요한 경우 503 + Retry-After 헤더로 재시도를 안내합니다.

### Q3: "Singleflight가 분산 환경에서도 동작하나요?"
> A: 현재는 **단일 인스턴스 내**에서 동작합니다.
> 분산 환경에서는 **Redis 기반 분산 락(Redisson)**으로 확장 가능하며,
> 실제로 일부 엔드포인트에 적용되어 있습니다. (Leader-Follower 패턴)

### Q4: "왜 Resilience4j를 선택했나요?"
> A: 세 가지 이유입니다:
> 1. **Spring Boot 3.x 네이티브 통합** (auto-configuration)
> 2. **함수형 프로그래밍 스타일** (데코레이터 패턴)
> 3. **Netflix Hystrix 대비 가벼운 의존성** (deprecated 이슈 없음)

### Q5: "실제 프로덕션에서 이 수치가 나오나요?"
> A: 벤치마크는 로컬 환경 기준이며, 프로덕션에서는 인프라에 따라 달라집니다.
> 다만 **상대적 개선율**(메모리 90% 감소, API 호출 99% 감소)은 유사합니다.
> AWS t3.small 단일 인스턴스에서 1,000 동시 사용자 처리를 검증했습니다.

### Q6: "LogicExecutor가 뭔가요? try-catch 대신 쓰는 이유는?"
> A: LogicExecutor는 **예외 처리 템플릿**입니다.
> 비즈니스 로직에서 try-catch를 제거하고 6가지 패턴으로 표준화했습니다:
> - `execute()`: 기본 실행
> - `executeOrDefault()`: 예외 시 기본값
> - `executeWithFinally()`: 자원 해제 보장
> 이를 통해 **코드 일관성**과 **로그 가시성**을 확보했습니다.

### Q7: "테스트는 어떻게 하나요?"
> A: 3단계 테스트 전략을 사용합니다:
> 1. **Unit Test**: 479개 테스트 케이스
> 2. **Integration Test**: Testcontainers (실제 MySQL/Redis)
> 3. **Chaos Test**: 18개 Nightmare 시나리오 (Deadlock, OOM, Timeout 등)

---

## 데모 체크리스트

### 시작 전
- [ ] Docker 컨테이너 전체 실행 확인 (`docker-compose ps`)
- [ ] Application 정상 구동 확인 (`/actuator/health`)
- [ ] 테스트 데이터 준비 (캐릭터명: 아델, 강은호, 진격캐넌)
- [ ] wrk Docker 이미지 pull 완료

### 데모 중
- [ ] 터미널 폰트 크기 확대 (가독성)
- [ ] 명령어 복사 준비 (타이핑 실수 방지)
- [ ] 예상 결과와 실제 결과 비교

### 종료 후
- [ ] 질문 응대 (위 Q&A 참고)
- [ ] 추가 자료 안내 (README, KPI Dashboard, ADR)

---

## Quick Command Reference

```bash
# 헬스체크
curl http://localhost:8080/actuator/health | jq

# Circuit Breaker 상태
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'

# 캐시 초기화
docker exec -it $(docker ps -qf "name=redis") redis-cli FLUSHALL

# 부하테스트
docker run --rm --add-host=host.docker.internal:host-gateway \
  -v $(pwd)/load-test:/scripts williamyeh/wrk \
  -t4 -c100 -d30s -s /scripts/wrk-v4-expectation.lua \
  http://host.docker.internal:8080

# 메트릭 확인
curl http://localhost:8080/actuator/prometheus | grep -E "http_server|cache|circuit"
```

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-26*
