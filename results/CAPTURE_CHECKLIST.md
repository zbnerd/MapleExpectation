# Performance Baseline Capture Checklist

## Status: ❌ Application Not Running

---

## Required Commands When Application is Running

### 1. Load Test with wrk
```bash
# If application is running on port 8080
wrk -t4 -c100 -d30s http://localhost:8080/api/v2/game/character/basic/ign
wrk -t4 -c100 -d30s http://localhost:8080/api/v2/calculator/upgrade/estimate
wrk -t4 -c100 -d30s http://localhost:8080/api/v2/calculator/cube/success-rate

# Save results
wrk -t4 -c100 -d30s http://localhost:8080/api/v2/game/character/basic/ign --timeout 30s -s ./scripts/wrk-report.lua > results/wrk_character_basic.json
```

### 2. Actuator Metrics
```bash
# Capture all available metrics
curl -s http://localhost:8080/actuator/metrics > results/actuator_metrics.json

# Specific important metrics
curl -s "http://localhost:8080/actuator/metrics/http.server.requests" > results/http_requests.json
curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used" > results/jvm_memory.json
curl -s "http://localhost:8080/actuator/metrics/jvm.threads.live" > results/jvm_threads.json
curl -s "http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls" > results/circuit_breaker.json
```

### 3. Cache Metrics
```bash
# Redis metrics via CLI
redis-cli info memory > results/redis_memory.json
redis-cli info stats > results/redis_stats.json
redis-cli info keyspace > results/redis_keyspace.json
```

### 4. Database Metrics
```bash
# MySQL performance metrics
mysql -u root -p -e "SHOW ENGINE INNODB STATUS;" > results/mysql_innodb_status.txt
mysql -u root -p -e "SHOW PROCESSLIST;" > results/mysql_processlist.txt
```

### 5. Application-Specific Metrics
```bash
# API response times (curl with timing)
time curl -s http://localhost:8080/api/v2/game/character/basic/ign > results/api_response_time.txt

# Custom endpoints if available
curl -s "http://localhost:8080/actuator/baselines" > results/baseline_metrics.json
```

---

## Prerequisites

Before running capture commands:

1. **Start Application:**
   ```bash
   ./gradlew bootRun
   ```

2. **Warm Up Cache:**
   ```bash
   # Make some API calls to populate cache
   curl -s http://localhost:8080/api/v2/game/character/basic/ign
   curl -s http://localhost:8080/api/v2/calculator/upgrade/estimate
   ```

3. **Verify Application Health:**
   ```bash
   curl -s http://localhost:8080/actuator/health | jq .
   ```

---

## What to Capture (Priority Order)

### 🔴 Priority 1 - Must Have
- wrk load test results
- HTTP server requests metrics
- JVM memory and thread metrics

### 🟡 Priority 2 - Important
- Circuit breaker metrics
- Redis memory and stats
- API response times

### 🟢 Priority 3 - Nice to Have
- Custom application metrics
- Database performance metrics
- JVM GC metrics

---

## Capture Timeline

- **2026-02-10 00:00:** ❌ Application not running - baseline deferred
- **Next capture window:** When `./gradlew bootRun` succeeds