# Chaos Test Quick Start Guide

> **Quick Reference**: Execute chaos and nightmare tests in the dedicated module
> **Updated**: 2026-02-11

---

## Local Development

### Run All Chaos & Nightmare Tests
```bash
./gradlew :module-chaos-test:chaosTest
```

### Run Specific Categories
```bash
# Network chaos only (Clock Drift, Thundering Herd)
./gradlew :module-chaos-test:chaosTestNetwork

# Resource chaos only (OOM, Disk Full, GC Pause)
./gradlew :module-chaos-test:chaosTestResource

# Core infrastructure chaos only
./gradlew :module-chaos-test:chaosTestCore

# Nightmare scenarios only (15 extreme tests)
./gradlew :module-chaos-test:nightmareTest
```

### Run Individual Test Class
```bash
# Specific chaos test
./gradlew :module-chaos-test:chaosTest --tests "*ClockDriftChaosTest"

# Specific nightmare test
./gradlew :module-chaos-test:chaosTest --tests "*ThunderingHerdNightmareTest"
```

### Run Specific Test Method
```bash
./gradlew :module-chaos-test:chaosTest \
  --tests "*ThunderingHerdNightmareTest.shouldMinimizeDbQueries"
```

---

## Test Profiles

| Profile | Description | Duration | Use Case |
|---------|-------------|----------|----------|
| `chaosTest` | All 22 chaos + nightmare tests | 20-30 min | Full chaos validation |
| `chaosTestNetwork` | Network failure injection | 5-10 min | Network resilience |
| `chaosTestResource` | Resource exhaustion | 10-15 min | Resource limits |
| `nightmareTest` | 15 nightmare scenarios | 15-20 min | Extreme scenarios |

---

## Environment Setup

### Prerequisites
```bash
# Start Docker Compose (MySQL, Redis)
docker-compose up -d

# Verify services
docker ps
curl http://localhost:6379/ping  # Redis
```

### Test Environment Variables
```bash
export SPRING_PROFILES_ACTIVE=chaos
export DOCKER_HOST=unix:///var/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=false
```

---

## CI/CD Execution

### Manual Trigger (GitHub Actions)
1. Navigate to **Actions** tab in repository
2. Select **Chaos & Nightmare Tests** workflow
3. Click **Run workflow**
4. Configure inputs:
   - **Chaos Category**: `all` (default), `network`, `resource`, `core`
   - **Skip Nightmare**: `false` (default), `true`

### Scheduled Execution
- **Schedule**: Daily at KST 00:00 (UTC 15:00)
- **Scope**: All 22 chaos/nightmare tests
- **Reports**: Uploaded as GitHub artifacts (14-day retention)

---

## Results & Reports

### Local Reports
```bash
# HTML report
open module-chaos-test/build/reports/tests/test/index.html

# XML results (CI integration)
cat module-chaos-test/build/test-results/test/*.xml
```

### CI Artifacts
- **Artifact Name**: `chaos-test-reports`, `nightmare-test-reports`
- **Retention**: 14 days
- **Contents**: HTML reports, JUnit XML, test logs

---

## Common Issues

### Issue: Docker Socket Permission Error
```bash
sudo usermod -aG docker $USER
newgrp docker
```

### Issue: Testcontainers Ryuk Disabled
```bash
# Enable Ryuk for proper cleanup
export TESTCONTAINERS_RYUK_DISABLED=false
```

### Issue: Port Conflicts
```bash
# Stop Docker Compose services before chaos tests
docker-compose down

# Let Testcontainers manage its own containers
./gradlew :module-chaos-test:chaosTest
```

---

## Documentation References

- **Full Architecture**: [chaos-test-module-architecture.md](/home/maple/MapleExpectation/docs/02_Technical_Guides/chaos-test-module-architecture.md)
- **Test Strategy**: [TEST_STRATEGY.md](/home/maple/MapleExpectation/docs/01_Chaos_Engineering/00_Overview/TEST_STRATEGY.md)
- **Nightmare Scenarios**: [docs/01_Chaos_Engineering/06_Nightmare/](/home/maple/MapleExpectation/docs/01_Chaos_Engineering/06_Nightmare/)
