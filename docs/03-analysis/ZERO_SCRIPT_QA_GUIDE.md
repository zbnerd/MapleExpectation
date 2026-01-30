# Zero Script QA Monitoring Guide
## MapleExpectation Project

---

## Quick Start (5 minutes)

### Step 1: Verify Docker Containers
```bash
# Check all containers are healthy
docker compose ps

# Expected Output:
# NAME           STATUS              PORTS
# maple-mysql    Up 3 hours          0.0.0.0:3306->3306/tcp
# redis-master   Up 3 hours (healthy) 0.0.0.0:6379->6379/tcp
# redis-slave    Up 3 hours          0.0.0.0:6380->6379/tcp
# maple-sentinel-1/2/3   Up 3 hours
```

### Step 2: Start Real-Time Log Monitoring
```bash
# Terminal 1: Start log streaming
docker compose logs -f 2>&1 | tee /tmp/qa_logs_$(date +%Y%m%d_%H%M%S).txt
```

### Step 3: Build Application (Skip Tests)
```bash
# Terminal 2: Build project
./gradlew clean build -x test
```

### Step 4: Start Application
```bash
# Terminal 3: Run Spring Boot with local profile
./gradlew bootRun --args='--spring.profiles.active=local'
```

### Step 5: Test API
```bash
# Terminal 4: Execute test requests
# Wait for "Started MapleExpectationApplication" in logs

# Example: Simple health check
curl -X GET http://localhost:8080/api/health

# Example: API test
curl -X GET http://localhost:8080/api/v2/game/character/123
```

---

## Environment Setup

### Required Environment Variables
```bash
# .env file or export in terminal
export DB_ROOT_PASSWORD=your_password
export DB_SCHEMA_NAME=maple_expectation
export DISCORD_WEBHOOK_URL=your_webhook_url
export TZ=Asia/Seoul
```

### Port Configuration
| Service | Host Port | Container Port | Status |
|---------|-----------|----------------|--------|
| MySQL | 3306 | 3306 | Running |
| Redis Master | 6379 | 6379 | Healthy |
| Redis Slave | 6380 | 6379 | Running |
| Sentinel-1 | 26379 | 26379 | Running |
| Sentinel-2 | 26380 | 26379 | Running |
| Sentinel-3 | 26381 | 26379 | Running |
| Spring Boot | 8080 | 8080 | (to start) |

---

## QA Monitoring Workflow

### Phase 1: Log Collection (Real-Time)
```bash
# Terminal 1: Docker logs
docker compose logs -f

# What to Monitor:
# 1. Application startup messages
# 2. Database connection success/failure
# 3. Redis connection status
# 4. Request/response logs with Request ID
# 5. Any ERROR level messages
```

### Phase 2: Manual API Testing
```bash
# Terminal 4: Execute test scenarios

# Health Check
curl -X GET http://localhost:8080/api/health \
  -H "X-Request-ID: req_test_001"

# Character Lookup
curl -X GET http://localhost:8080/api/v2/game/character/123 \
  -H "X-Request-ID: req_test_002"

# Equipment Upgrade Cost
curl -X POST http://localhost:8080/api/v2/calculator/upgrade-cost \
  -H "Content-Type: application/json" \
  -H "X-Request-ID: req_test_003" \
  -d '{
    "itemId": 1004000,
    "enhancement": 10,
    "targetEnhancement": 15,
    "boosterPrice": 100
  }'
```

### Phase 3: Real-Time Log Analysis

#### Pattern 1: Request ID Tracing
```bash
# Terminal 5: Monitor specific request
docker compose logs -f | grep 'req_test_001'

# Expected Flow:
# 1. nginx: Request received with X-Request-ID header
# 2. api: Request processing started
# 3. api: Database query with same request_id
# 4. api: Response sent
```

#### Pattern 2: Error Detection
```bash
# Terminal 5: Filter ERROR logs only
docker compose logs -f | grep '"level":"ERROR"'

# Immediate Actions on Error:
# 1. Capture full request context
# 2. Note the Request ID
# 3. Analyze related logs
# 4. Document in issue report
```

#### Pattern 3: Performance Monitoring
```bash
# Terminal 5: Find slow responses
docker compose logs -f | grep -E '"duration_ms":[0-9]{4,}'

# Thresholds:
# - < 100ms: Excellent
# - 100-500ms: Good
# - 500-1000ms: Acceptable
# - > 1000ms: Slow (investigate)
# - > 3000ms: Critical (report)
```

#### Pattern 4: Redis/MySQL Connectivity
```bash
# Terminal 5: Connection issues
docker compose logs -f | grep -i "connection"
docker compose logs -f | grep -i "timeout"
docker compose logs -f | grep -i "refuse"

# Indicates potential:
# - Database connection pool exhaustion
# - Network latency
# - Resource constraints
```

---

## Issue Detection Patterns

### Critical Issues (Immediate Report)

#### 1. ERROR Level Logs
```json
{
  "timestamp": "2026-01-30T12:34:56.789Z",
  "level": "ERROR",
  "service": "api",
  "request_id": "req_abc123",
  "message": "Database connection failed",
  "data": {
    "error": "Connection timeout",
    "duration_ms": 5000
  }
}
```
**Action**: Document immediately with full context

#### 2. 5xx Status Codes
```json
{
  "data": {
    "status": 500,
    "path": "/api/v2/calculator/upgrade-cost",
    "duration_ms": 45
  }
}
```
**Action**: Investigate server-side error, check logs for exception

#### 3. Connection Failures
```
[ERROR] Unable to connect to Redis: Connection refused
[ERROR] MySQL Connection Pool Exhausted
```
**Action**: Check container health, review connection pool settings

### Warning Issues (Report + Investigation)

#### 4. Slow Responses (1000-3000ms)
```json
{
  "duration_ms": 1500,
  "path": "/api/v2/calculator/complex-calculation"
}
```
**Action**:
- Identify bottleneck (DB query? External API?)
- Check database query performance
- Review code for optimization opportunities

#### 5. Consecutive Failures
```
3+ identical errors in 1 minute on same endpoint
```
**Action**:
- Pattern indicates systemic issue
- Check configuration
- Review recent code changes
- Consider circuit breaker activation

#### 6. Request ID Not Propagated
```
Logs show requests without Request ID or inconsistent IDs across services
```
**Action**:
- Verify Request ID generation
- Check header propagation in middlewares
- Document for logging improvements

---

## Issue Documentation Template

### Example Issue Report

```markdown
## ISSUE-001: Slow Equipment Upgrade Cost Calculation

**Request ID**: req_test_003
**Severity**: Warning (1500ms > 1000ms threshold)
**Service**: api
**Endpoint**: POST /api/v2/calculator/upgrade-cost
**Time**: 2026-01-30 12:34:56.789 UTC

### Reproduction
1. Call POST /api/v2/calculator/upgrade-cost
2. With itemId=1004000, enhancement=10, targetEnhancement=15
3. Response takes ~1500ms

### Related Logs
```json
{
  "timestamp": "2026-01-30T12:34:56.789Z",
  "level": "INFO",
  "service": "api",
  "request_id": "req_test_003",
  "message": "Upgrade cost calculation started",
  "data": {
    "itemId": 1004000,
    "enhancement": 10
  }
}
{
  "timestamp": "2026-01-30T12:34:58.289Z",
  "level": "INFO",
  "service": "api",
  "request_id": "req_test_003",
  "message": "Database query: SELECT equipment FROM...",
  "data": {
    "query_duration_ms": 800
  }
}
{
  "timestamp": "2026-01-30T12:34:58.500Z",
  "level": "INFO",
  "service": "api",
  "request_id": "req_test_003",
  "message": "Response sent",
  "data": {
    "status": 200,
    "total_duration_ms": 1500
  }
}
```

### Root Cause Analysis
- Database query taking 800ms (expected < 200ms)
- Possible cause:
  1. Missing database index on equipment lookup
  2. Redis cache miss/timeout
  3. Join operation with large table

### Recommended Fix
1. Check if `equipment` table has index on `itemId`
2. Verify Redis cache is being used
3. Optimize query with better indexes
4. Consider caching equipment data in L1 cache

### Files to Review
- `src/main/java/maple/expectation/service/CalculatorService.java:125`
- `src/main/java/maple/expectation/mapper/EquipmentMapper.java:50`

### Testing After Fix
```bash
curl -X POST http://localhost:8080/api/v2/calculator/upgrade-cost \
  -H "X-Request-ID: req_followup_001" \
  -d '{"itemId": 1004000, "enhancement": 10, "targetEnhancement": 15}'

# Expected: duration_ms < 300ms
```
```

---

## Logging Standard Validation

### Check 1: JSON Format
```bash
# Verify logs are valid JSON
docker compose logs api | head -20 | jq . 2>/dev/null

# If valid, all logs should parse without error
# If error: "parse error", logs are NOT properly formatted
```

### Check 2: Required Fields
Verify each log contains:
```json
{
  "timestamp": "ISO 8601 format (REQUIRED)",
  "level": "DEBUG|INFO|WARNING|ERROR (REQUIRED)",
  "service": "service name (REQUIRED)",
  "request_id": "req_xxx (REQUIRED for API logs)",
  "message": "log message (REQUIRED)",
  "data": { "optional": "additional context" }
}
```

### Check 3: Request ID Propagation
```bash
# Pick a request and trace it
docker compose logs | grep 'req_test_003'

# Should see same ID in:
# 1. nginx (entry point)
# 2. api (business logic)
# 3. MySQL queries
# 4. Redis operations
```

### Check 4: Response Time Tracking
```bash
# Look for duration_ms in responses
docker compose logs api | grep 'duration_ms'

# Pattern: {"duration_ms": 45, "status": 200}
# Verify times are reasonable for operation type
```

---

## Monitoring Commands Reference

### Essential Commands
```bash
# Start log streaming
docker compose logs -f

# Specific service only
docker compose logs -f db
docker compose logs -f redis-master

# Last N lines
docker compose logs --tail=50

# Since specific time
docker compose logs --since "10m"

# Until specific time
docker compose logs --until "2m"

# Save to file
docker compose logs > logs_backup.txt

# With timestamps
docker compose logs --timestamps
```

### Filtering Commands
```bash
# Filter by level
docker compose logs -f | grep '"level":"ERROR"'
docker compose logs -f | grep '"level":"WARN"'

# Filter by service
docker compose logs -f | grep 'service":"api"'

# Filter by status code
docker compose logs -f | grep '"status":5'    # 5xx errors
docker compose logs -f | grep '"status":40'   # 4xx errors

# Filter by request ID
docker compose logs -f | grep 'req_abc123'

# Filter by performance
docker compose logs -f | grep -E '"duration_ms":[0-9]{4,}'

# Filter multiple patterns
docker compose logs -f | grep -E '(ERROR|status.*5|duration.*[4-9][0-9]{3})'
```

### Analysis Commands
```bash
# Count errors by type
docker compose logs | grep '"level":"ERROR"' | jq '.data.error' | sort | uniq -c

# Find slowest requests
docker compose logs | jq 'select(.data.duration_ms > 1000)' | sort -k.data.duration_ms

# Top endpoints by request count
docker compose logs | jq -r '.data.path' | sort | uniq -c | sort -rn | head -10

# Request success rate
docker compose logs api | jq '.data.status' | sort | uniq -c
```

---

## Troubleshooting

### Issue: Application won't start
```bash
# Check database connection
docker compose logs db | tail -20

# Check Redis connection
docker compose logs redis-master | tail -20

# Check port conflicts
lsof -i :8080  # Port already in use?

# Solution: Check logs for specific error message
```

### Issue: Slow startup
```bash
# Normal startup: 30-60 seconds
# Check for:
# 1. Database migration taking time
# 2. Cache initialization
# 3. Spring component scanning

# Monitor with:
docker compose logs -f | grep -i "started\|completed\|initialized"
```

### Issue: High response times
```bash
# Check database query performance
docker compose logs api | grep -E '"query.*duration_ms":[0-9]{4,}'

# Check Redis operations
docker compose logs redis-master | tail -50

# Check MySQL slow queries
docker exec -it maple-mysql mysql -u root -p -e "SELECT * FROM mysql.slow_log;"
```

### Issue: Connection errors
```bash
# Check Docker network
docker network inspect maple-network

# Check service DNS resolution
docker exec maple-mysql ping redis-master

# Verify connection pools
docker compose logs api | grep -i "pool\|connection"
```

---

## Success Criteria

### Phase 1: Infrastructure Ready
- [ ] Docker containers all UP
- [ ] MySQL initialized with maple_expectation database
- [ ] Redis master-slave replication synced
- [ ] Sentinels monitoring active

### Phase 2: Application Started
- [ ] No startup errors
- [ ] API listening on port 8080
- [ ] Database connection successful
- [ ] Redis connection successful

### Phase 3: Basic Functionality
- [ ] Health check endpoint responds
- [ ] Character lookup works
- [ ] Calculator endpoints return results
- [ ] Response times reasonable (< 500ms)

### Phase 4: Logging Quality
- [ ] All API logs are valid JSON
- [ ] Request ID present and propagated
- [ ] Response times tracked in logs
- [ ] Errors properly categorized

### Phase 5: No Critical Issues
- [ ] No ERROR level logs
- [ ] No 5xx status codes
- [ ] No connection failures
- [ ] No consecutive failures

---

## Next Steps

After QA Monitoring Complete:
1. Review all detected issues
2. Prioritize fixes (Critical → Warning → Info)
3. Implement fixes
4. Re-run monitoring to verify
5. Move to next phase (Phase 5, 6, 7, etc.)

---

**Document Version**: 1.0
**Last Updated**: 2026-01-30
**Status**: Ready for Testing

