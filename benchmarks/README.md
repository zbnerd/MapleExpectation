# ACL Pipeline Performance Benchmark Guide (Issue #300)

**Objective**: Validate 5x throughput improvement of ACL pipeline over baseline REST approach using `wrk`.

---

## Prerequisites

1. **wrk installed**:
   ```bash
   # Ubuntu/Debian
   sudo apt-get install wrk

   # macOS
   brew install wrk

   # Verify
   wrk --version
   ```

2. **Application running**:
   ```bash
   # Start application with ACL disabled (baseline)
   ./gradlew bootRun --args='--spring.profiles.active=prod --scheduler.nexon-data-collection.enabled=false'

   # Start application with ACL enabled (pipeline)
   ./gradlew bootRun --args='--spring.profiles.active=prod --scheduler.nexon-data-collection.enabled=true'
   ```

3. **Test data**: Ensure at least 5 characters exist in database with OCIDs known.

---

## Benchmark Scenarios

### Scenario 1: Baseline (Direct REST → DB)

**Configuration**: `scheduler.nexon-data-collection.enabled=false`

```bash
# Warm-up
wrk -t 4 -c 10 -d 10s --latency http://localhost:8080/api/v4/characters/test-character/expectation

# Actual benchmark (30s, 10 concurrent connections)
wrk -t 4 -c 10 -d 30s -s benchmarks/wrk/acl-benchmark.lua -- test-character http://localhost:8080/api/v4/characters/

# Higher load (50 concurrent connections)
wrk -t 8 -c 50 -d 30s -s benchmarks/wrk/acl-benchmark.lua -- test-character http://localhost:8080/api/v4/characters/

# Max load (100 concurrent connections)
wrk -t 12 -c 100 -d 30s -s benchmarks/wrk/acl-benchmark.lua -- test-character http://localhost:8080/api/v4/characters/
```

**Expected Results** (Baseline):
- Lower throughput due to synchronous REST calls
- Higher P95/P99 latency
- Potential thread pool exhaustion at high concurrency

---

### Scenario 2: ACL Pipeline (Queue + Batch)

**Configuration**: `scheduler.nexon-data-collection.enabled=true`

```bash
# Enable scheduler and restart application
# Wait for initial data collection (30s after startup)

# Run same benchmarks as Scenario 1
wrk -t 4 -c 10 -d 30s -s benchmarks/wrk/acl-benchmark.lua -- test-character http://localhost:8080/api/v4/characters/

wrk -t 8 -c 50 -d 30s -s benchmarks/wrk/acl-benchmark.lua -- test-character http://localhost:8080/api/v4/characters/

wrk -t 12 -c 100 -d 30s -s benchmarks/wrk/acl-benchmark.lua -- test-character http://localhost:8080/api/v4/characters/
```

**Expected Results** (ACL Pipeline):
- **5x+ higher throughput** due to cache hits from proactive collection
- Lower P95/P99 latency
- Better stability under high concurrency

---

## Metrics Collection

### Key Metrics

| Metric | Description | Target |
|--------|-------------|--------|
| **Requests/sec** | Throughput | 5x improvement |
| **P50 Latency** | Median response time | Lower or equal |
| **P95 Latency** | 95th percentile | 50% reduction |
| **P99 Latency** | 99th percentile | 50% reduction |
| **Success Rate** | 200 OK responses | 100% |
| **Errors** | 4xx/5xx responses | 0 |

### Output Format

```bash
# wrk output example
Running 30s test @ http://localhost:8080/api/v4/characters/test-character/expectation
  4 threads and 10 connections
  Thread Stats   Avg      Stdev     +/-   Stdev
    Latency    10.50ms   2.30ms   75.03%
    Req/Sec   950.23     50.12     89.45%
  285067 requests in 30.00s, 45.23MB read
Requests/sec:   9502.23
Transfer/sec:      1.51MB

=== ACL Pipeline Benchmark Results ===
Successful requests: 285067
Total requests: 285067
Success rate: 100.00%

--- Latency Distribution ---
Mean: 10.50 ms
P50: 9.80 ms
P95: 15.20 ms
P99: 25.40 ms
Max: 120.00 ms

--- Throughput ---
Requests/sec: 9502.23
```

---

## Comparison Methodology

### Step 1: Establish Baseline

Run Scenario 1 (ACL disabled) and record:
```
Baseline_10c.txt
Baseline_50c.txt
Baseline_100c.txt
```

### Step 2: Measure ACL Pipeline

Run Scenario 2 (ACL enabled) and record:
```
Pipeline_10c.txt
Pipeline_50c.txt
Pipeline_100c.txt
```

### Step 3: Calculate Improvement

For each concurrency level:

```bash
# Extract requests/sec from baseline
baseline_rps=$(grep "Requests/sec:" Baseline_10c.txt | awk '{print $2}')

# Extract requests/sec from pipeline
pipeline_rps=$(grep "Requests/sec:" Pipeline_10c.txt | awk '{print $2}')

# Calculate improvement ratio
echo "scale=2; $pipeline_rps / $baseline_rps" | bc
```

### Step 4: Validation Criteria

✅ **PASS** if:
- All concurrency levels show ≥ 5x improvement
- P95 latency reduced by ≥ 50%
- Zero errors (0 failures)
- Success rate = 100%

❌ **FAIL** if:
- Any metric below threshold
- High error rate (> 1%)
- Application crashes or OOM

---

## Example Results Template

```markdown
## ACL Pipeline Performance Benchmark Results

Date: 2026-02-07
Environment: Local (t3.small equivalent)
Database: MySQL 8.0 (Testcontainers)
Cache: Redis 7-alpine

### Throughput (Requests/sec)

| Concurrency | Baseline | ACL Pipeline | Improvement |
|-------------|----------|--------------|-------------|
| 10          | 1,200    | 9,500        | 7.9x ✅ |
| 50          | 1,800    | 12,000       | 6.7x ✅ |
| 100         | 2,000    | 15,000       | 7.5x ✅ |

### Latency (ms)

| Metric      | Baseline | ACL Pipeline | Improvement |
|-------------|----------|--------------|-------------|
| P50         | 45.20    | 9.80         | 78% ↓ ✅ |
| P95         | 120.50   | 15.20        | 87% ↓ ✅ |
| P99         | 250.80   | 25.40        | 90% ↓ ✅ |

### Validation
- ✅ 5x throughput improvement: ACHIEVED (7.9x)
- ✅ P95 latency reduction: ACHIEVED (87%)
- ✅ Zero errors: PASSED (0 failures)
- ✅ 100% success rate: PASSED

**Conclusion**: ACL pipeline exceeds performance targets.
```

---

## Troubleshooting

### Issue: "Connection refused"

**Cause**: Application not running or wrong port.

**Fix**:
```bash
# Check if port 8080 is listening
netstat -tuln | grep 8080

# Verify application logs
tail -f logs/application.log | grep "Started ExpectationApplication"
```

### Issue: Low throughput for both scenarios

**Cause**: Database bottleneck or missing test data.

**Fix**:
```bash
# Verify characters exist in database
mysql -u root -p -e "SELECT COUNT(*) FROM maple_expectation.game_character;"

# Insert test data if needed
# (Use existing character creation scripts)
```

### Issue: OutOfMemoryError during benchmark

**Cause**: Heap size too small.

**Fix**:
```bash
export JAVA_OPTS="-Xmx2g -Xms2g"
./gradlew bootRun
```

---

## Next Steps

After benchmark completion:

1. **Document results** in `docs/04_Reports/acl-performance-benchmark-results.md`
2. **Update DoD** for Issue #300 with performance evidence
3. **Create Grafana dashboards** (Task #46) for ongoing monitoring
4. **Consider optimization** if targets not met

---

## References

- **wrk GitHub**: https://github.com/wg/wrk
- **wrk Lua Scripting**: https://github.com/wg/wrk/tree/master/scripts
- **Issue #300**: ACL 3-Stage Pipeline
- **ADR-018**: Strategy Pattern for ACL
