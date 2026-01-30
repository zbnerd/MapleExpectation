# QA Monitoring Checklist
## Zero Script QA - MapleExpectation

---

## Pre-Monitoring Verification

### Infrastructure Health Check
- [ ] MySQL container: `docker compose ps | grep maple-mysql`
  - Status: UP (at least 1 second)
  - Port: 3306 open
- [ ] Redis Master: `docker compose ps | grep redis-master`
  - Status: UP (healthy)
  - Port: 6379 open
- [ ] Redis Slave: `docker compose ps | grep redis-slave`
  - Status: UP
  - Port: 6380 open
- [ ] All Sentinels: 3 instances UP
  - Ports: 26379, 26380, 26381 open

### Environment Variables
- [ ] `DB_ROOT_PASSWORD` exported
- [ ] `DB_SCHEMA_NAME` = `maple_expectation`
- [ ] `TZ` = `Asia/Seoul`
- [ ] `DISCORD_WEBHOOK_URL` (optional but recommended)

### Directory Structure
- [ ] `src/main/resources/application-local.yml` exists
- [ ] `docker-compose.yml` exists
- [ ] `docs/03-analysis/` directory exists
- [ ] Gradle wrapper: `./gradlew` executable

---

## Monitoring Setup

### Terminal 1: Real-Time Logs
- [ ] Open new terminal window
- [ ] Navigate to project root: `cd /home/geek/maple_expectation/MapleExpectation`
- [ ] Run: `docker compose logs -f 2>&1 | tee /tmp/qa_logs_$(date +%Y%m%d_%H%M%S).txt`
- [ ] Verify output is streaming (should see existing container logs)
- [ ] Keep terminal open during entire test

### Terminal 2: Build
- [ ] Open new terminal window
- [ ] Navigate to project root
- [ ] Run: `./gradlew clean build -x test`
- [ ] Wait for: `BUILD SUCCESSFUL`
- [ ] Keep terminal open (ready for next step)

### Terminal 3: Application Startup
- [ ] Open new terminal window
- [ ] Navigate to project root
- [ ] Run: `./gradlew bootRun --args='--spring.profiles.active=local'`
- [ ] Look for "Started MapleExpectationApplication" in Terminal 1 logs
- [ ] Verify port 8080 is listening: `lsof -i :8080`
- [ ] Keep terminal open (application running)

### Terminal 4: Test Execution
- [ ] Open new terminal window
- [ ] Navigate to project root
- [ ] Ready to execute API tests

### Terminal 5: Log Analysis (Optional but Recommended)
- [ ] Open new terminal window
- [ ] Use for real-time log filtering
- [ ] Ready for pattern analysis

---

## Application Startup Verification

### Monitoring Logs for Startup
In Terminal 1, watch for these messages:

- [ ] Database Connection
  ```
  Hibernate: SELECT 1
  (or similar database test query)
  ```

- [ ] Redis Connection
  ```
  Redisson node connected successfully
  (or similar Redis success message)
  ```

- [ ] Application Started
  ```
  Started MapleExpectationApplication in X seconds
  ```

### Quick Sanity Check
```bash
# Terminal 4: Test if app is responding
curl -X GET http://localhost:8080/api/health -v

# Expected: HTTP 200 OK
# If timeout or refused: application not ready yet
# If 404: wrong endpoint
```

---

## Test Execution Plan

### Test 1: Simple Health Check
**Purpose**: Verify basic connectivity
**Execution Time**: ~500ms

```bash
# Terminal 4
curl -X GET http://localhost:8080/api/health \
  -H "X-Request-ID: req_health_001" \
  -v
```

**In Terminal 1, Watch For:**
- [ ] Request received in logs
- [ ] Request ID: `req_health_001`
- [ ] Response status: 200
- [ ] Response time: < 100ms

### Test 2: Character Lookup
**Purpose**: Test database query
**Execution Time**: ~100-500ms

```bash
# Terminal 4
curl -X GET "http://localhost:8080/api/v2/game/character/maple123" \
  -H "X-Request-ID: req_char_001" \
  -v
```

**In Terminal 1, Watch For:**
- [ ] Request received
- [ ] Database query executed
- [ ] Request ID propagated
- [ ] Response status: 200 or 404
- [ ] Response time: < 500ms

### Test 3: Equipment Calculator
**Purpose**: Test business logic + database
**Execution Time**: ~500-1000ms

```bash
# Terminal 4
curl -X POST "http://localhost:8080/api/v2/calculator/upgrade-cost" \
  -H "Content-Type: application/json" \
  -H "X-Request-ID: req_calc_001" \
  -d '{
    "itemId": 1004000,
    "enhancement": 10,
    "targetEnhancement": 15,
    "boosterPrice": 100
  }' \
  -v
```

**In Terminal 1, Watch For:**
- [ ] Request received
- [ ] Equipment lookup in database
- [ ] Calculation logic executed
- [ ] Response status: 200
- [ ] Response time: < 1000ms

### Test 4: Error Case (Expected Failure)
**Purpose**: Verify error handling and logging
**Execution Time**: ~50-200ms

```bash
# Terminal 4 - Invalid item ID
curl -X POST "http://localhost:8080/api/v2/calculator/upgrade-cost" \
  -H "Content-Type: application/json" \
  -H "X-Request-ID: req_error_001" \
  -d '{
    "itemId": 9999999,
    "enhancement": 10,
    "targetEnhancement": 15,
    "boosterPrice": 100
  }' \
  -v
```

**In Terminal 1, Watch For:**
- [ ] ERROR or WARN level log
- [ ] Proper error message
- [ ] Response status: 400 or 404
- [ ] Request ID still present

### Test 5: Concurrent Requests
**Purpose**: Test under load, monitor pool usage
**Execution Time**: ~2-5 seconds

```bash
# Terminal 4 - Run 10 concurrent requests
for i in {1..10}; do
  curl -s -X GET "http://localhost:8080/api/v2/game/character/test$i" \
    -H "X-Request-ID: req_concurrent_$i" &
done
wait
```

**In Terminal 1, Watch For:**
- [ ] All 10 requests logged
- [ ] Different request IDs
- [ ] No connection pool errors
- [ ] Response times reasonable
- [ ] No 5xx errors

---

## Real-Time Log Monitoring

### Pattern 1: Track Single Request
**In Terminal 5:**

```bash
# Replace req_calc_001 with actual request ID
docker compose logs -f | grep 'req_calc_001'

# Expected output:
# Line 1: Request starts
# Line 2-N: Internal processing
# Last Line: Response sent
```

**Success Criteria:**
- [ ] Request ID visible across all lines
- [ ] Logical progression (start → processing → end)
- [ ] Timestamps in order
- [ ] All services have same request ID

### Pattern 2: Error Detection
**In Terminal 5:**

```bash
docker compose logs -f | grep '"level":"ERROR"'
```

**Action if Error Found:**
- [ ] Note the exact error message
- [ ] Identify affected request ID
- [ ] Check request parameters
- [ ] Document in ISSUE file
- [ ] Suggest fix based on error

### Pattern 3: Performance Monitoring
**In Terminal 5:**

```bash
docker compose logs -f | grep -E '"duration_ms":[0-9]{4,}'
```

**Analysis:**
- [ ] Note all responses > 1000ms
- [ ] Identify pattern (same endpoint? same parameters?)
- [ ] Check if reproducible
- [ ] Document if > 3000ms

### Pattern 4: Connection Issues
**In Terminal 5:**

```bash
docker compose logs -f | grep -iE "(connection|timeout|refused|pool)"
```

**Action if Found:**
- [ ] Verify container health: `docker compose ps`
- [ ] Check resource usage: `docker stats`
- [ ] Review error message context
- [ ] Note if persistent or one-time

---

## Issue Documentation

### Format for Each Issue Found

**Issue Title**: [Create descriptive title]

**Request ID**: [From logs]

**Severity**:
- [ ] Critical (ERROR or 5xx)
- [ ] Warning (> 1000ms or 4xx)
- [ ] Info (logging quality)

**Test Steps**:
1. [Exact steps to reproduce]
2. [Parameters used]
3. [Expected vs actual result]

**Logs**:
```json
[Paste relevant log entries]
```

**Root Cause**:
[Your analysis]

**Suggested Fix**:
[Specific code location and suggested change]

**File Location**: `docs/03-analysis/zero-script-qa-2026-01-30.md`

---

## Post-Test Analysis

### Quick Assessment
After all tests complete:

- [ ] **Total Issues Found**: ___
  - [ ] Critical: ___
  - [ ] Warning: ___
  - [ ] Info: ___

- [ ] **Pass Rate**: ___ %
  - [ ] Success Criteria: 85% pass rate for Phase 4

- [ ] **Logging Quality**: ___
  - [ ] JSON Format: Good / Fair / Poor
  - [ ] Request ID Propagation: Good / Fair / Poor
  - [ ] Response Metrics: Good / Fair / Poor

### Success Evaluation

**Phase 4 (API) Complete When:**
- [ ] No ERROR level logs
- [ ] No 5xx status codes
- [ ] No connection failures
- [ ] Response times reasonable (< 1000ms avg)
- [ ] Request IDs properly tracked
- [ ] All basic endpoints working

**Ready for Next Phase When:**
- [ ] All critical issues resolved
- [ ] Pass rate >= 85%
- [ ] No recurring errors
- [ ] Logging standards met

---

## Troubleshooting Guide

### Application Won't Start
**Symptom**: "Connection refused" or "Port already in use"

```bash
# Check if port 8080 is in use
lsof -i :8080

# Kill process if needed
kill -9 <PID>

# Verify database is accessible
docker exec -it maple-mysql mysql -u root -p -e "SELECT 1"
```

### Slow Startup (> 2 minutes)
**Symptom**: "Started MapleExpectationApplication" appears after 2+ minutes

```bash
# Check database initialization
docker compose logs db | grep -i "init\|migration"

# Check if schema needs migration
docker exec -it maple-mysql mysql -u root -p maple_expectation -e "SHOW TABLES;" | wc -l

# If < 20 tables, Hibernate ddl-auto is still running
```

### High Response Times
**Symptom**: All requests > 1000ms

```bash
# Check database connection pool
docker compose logs api | grep -i "pool\|connection"

# Check if there's lock contention
docker exec -it maple-mysql mysql -u root -p -e "SHOW PROCESSLIST;"

# Check Redis connectivity
docker exec -it redis-master redis-cli PING
```

### Logs Not Appearing
**Symptom**: Docker logs show no application output

```bash
# Check if application actually started
lsof -i :8080

# If not listening, check terminal 3 for errors
# Application may have failed to start

# Force rebuild
./gradlew clean
./gradlew bootRun --args='--spring.profiles.active=local'
```

---

## Completion Checklist

### All Tests Executed
- [ ] Test 1: Health Check
- [ ] Test 2: Character Lookup
- [ ] Test 3: Equipment Calculator
- [ ] Test 4: Error Case
- [ ] Test 5: Concurrent Requests

### Issues Documented
- [ ] Critical issues (if any): Documented and categorized
- [ ] Warning issues (if any): Documented with analysis
- [ ] All with root cause and suggested fix

### Report Generated
- [ ] Main report: `docs/03-analysis/zero-script-qa-2026-01-30.md`
- [ ] Update with actual findings
- [ ] Include all issue details
- [ ] Add performance baseline

### Ready for Next Steps
- [ ] Decision made: Proceed to Phase 5 or fix issues?
- [ ] If fixing: Issues assigned and tracked
- [ ] If proceeding: All documentation updated
- [ ] Git commit with QA results (if approved)

---

## Notes Section

### Observations During Testing:
```
[Space for tester notes and observations]
```

### Questions/Blockers:
```
[Space for any blocking issues or questions]
```

### Next Phase Readiness:
```
[Assessment of readiness for next phase]
```

---

**Checklist Version**: 1.0
**Project**: MapleExpectation
**Phase**: Phase 4 (API Testing)
**Date**: 2026-01-30

