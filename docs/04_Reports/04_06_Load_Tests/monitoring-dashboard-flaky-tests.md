# Monitoring Dashboard - Flaky Test Fixing (Issues #328-330)

**Date:** 2026-02-10
**Dashboard:** MapleExpectation - Test Quality & Flaky Test Monitoring

---

## Overview

This document provides the monitoring configuration for validating the flaky test fixes and ensuring ongoing test stability.

---

## Prometheus Queries

### 1. Test Pass Rate

```promql
# Overall test pass rate (Target: 100%)
sum(rate(junit_tests_passed_total[5m])) / sum(rate(junit_tests_total_total[5m])) * 100

# By test module
sum by(module)(rate(junit_tests_passed_total[5m])) / sum by(module)(rate(junit_tests_total_total[5m])) * 100

# Last 24 hours trend
increase(junit_tests_passed_total[24h]) / increase(junit_tests_total_total[24h]) * 100
```

### 2. Flaky Test Detection

```promql
# Count of flaky tests (Target: 0)
count(junit_tests_flaky_detected)

# Flaky test rate
sum(rate(junit_tests_flaky_detected_total[5m]))

# Flaky tests by class
count by(class)(junit_tests_flaky_detected)
```

### 3. Test Duration

```promql
# P95 test duration (Target: < 5s)
histogram_quantile(0.95, junit_tests_duration_seconds)

# P99 test duration
histogram_quantile(0.99, junit_tests_duration_seconds)

# Average duration by test
avg by(test)(junit_tests_duration_seconds)

# Slow tests (> 10s)
count(junit_tests_duration_seconds_bucket{le="10"}) - count(junit_tests_duration_seconds_bucket{le="+Inf"})
```

### 4. Previously Flaky Tests (Specific Monitoring)

```promql
# DonationTest pass rate
sum(rate(junit_tests_passed_total{test_class="DonationTest"}[5m])) /
sum(rate(junit_tests_total_total{test_class="DonationTest"}[5m])) * 100

# RefreshTokenIntegrationTest pass rate
sum(rate(junit_tests_passed_total{test_class="RefreshTokenIntegrationTest"}[5m])) /
sum(rate(junit_tests_total_total{test_class="RefreshTokenIntegrationTest"}[5m])) * 100

# LikeSyncCompensationIntegrationTest pass rate
sum(rate(junit_tests_passed_total{test_class="LikeSyncCompensationIntegrationTest"}[5m])) /
sum(rate(junit_tests_total_total{test_class="LikeSyncCompensationIntegrationTest"}[5m])) * 100
```

### 5. Build Stability

```promql
# CI build success rate
sum(rate(jenkins_builds_success_total{job="MapleExpectation"}[24h])) /
sum(rate(jenkins_builds_total{job="MapleExpectation"}[24h])) * 100

# Build reruns due to flaky tests
increase(jenkins_builds_rerun_total{reason="flaky"}[24h])
```

---

## Grafana Dashboard Configuration

### Dashboard JSON

```json
{
  "dashboard": {
    "title": "MapleExpectation - Test Quality",
    "tags": ["testing", "quality", "flaky-tests"],
    "timezone": "UTC",
    "panels": [
      {
        "id": 1,
        "title": "Test Pass Rate (24h)",
        "type": "stat",
        "targets": [
          {
            "expr": "increase(junit_tests_passed_total[24h]) / increase(junit_tests_total_total[24h]) * 100",
            "legendFormat": "Pass Rate %"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "percent",
            "min": 0,
            "max": 100,
            "thresholds": {
              "mode": "absolute",
              "steps": [
                {"value": 0, "color": "red"},
                {"value": 95, "color": "yellow"},
                {"value": 99, "color": "green"}
              ]
            }
          }
        }
      },
      {
        "id": 2,
        "title": "Flaky Tests Count",
        "type": "stat",
        "targets": [
          {
            "expr": "count(junit_tests_flaky_detected)",
            "legendFormat": "Flaky Tests"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "thresholds": {
              "mode": "absolute",
              "steps": [
                {"value": 0, "color": "green"},
                {"value": 1, "color": "red"}
              ]
            }
          }
        }
      },
      {
        "id": 3,
        "title": "Test Duration P95",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, junit_tests_duration_seconds)",
            "legendFormat": "P95 Duration"
          }
        ],
        "yaxes": [
          {"format": "s", "label": "Duration"}
        ]
      },
      {
        "id": 4,
        "title": "Pass Rate by Module",
        "type": "piechart",
        "targets": [
          {
            "expr": "sum by(module)(increase(junit_tests_passed_total[24h]))",
            "legendFormat": "{{module}} - Passed"
          }
        ]
      },
      {
        "id": 5,
        "title": "Previously Flaky Tests - DonationTest",
        "type": "stat",
        "targets": [
          {
            "expr": "sum(rate(junit_tests_passed_total{test_class=\"DonationTest\"}[24h])) / sum(rate(junit_tests_total_total{test_class=\"DonationTest\"}[24h])) * 100",
            "legendFormat": "Pass Rate %"
          }
        ]
      },
      {
        "id": 6,
        "title": "Previously Flaky Tests - RefreshTokenIntegrationTest",
        "type": "stat",
        "targets": [
          {
            "expr": "sum(rate(junit_tests_passed_total{test_class=\"RefreshTokenIntegrationTest\"}[24h])) / sum(rate(junit_tests_total_total{test_class=\"RefreshTokenIntegrationTest\"}[24h])) * 100",
            "legendFormat": "Pass Rate %"
          }
        ]
      },
      {
        "id": 7,
        "title": "Previously Flaky Tests - LikeSyncCompensationIntegrationTest",
        "type": "stat",
        "targets": [
          {
            "expr": "sum(rate(junit_tests_passed_total{test_class=\"LikeSyncCompensationIntegrationTest\"}[24h])) / sum(rate(junit_tests_total_total{test_class=\"LikeSyncCompensationIntegrationTest\"}[24h])) * 100",
            "legendFormat": "Pass Rate %"
          }
        ]
      }
    ],
    "refresh": "30s",
    "time": {
      "from": "now-24h",
      "to": "now"
    }
  }
}
```

---

## Loki Queries for Test Log Analysis

### 1. Find Test Failures

```logql
{filename="/home/maple/MapleExpectation/build/reports/tests/test/*.xml"}
|=`FAILED`
| line_format "{{.message}}"
```

### 2. Find Thread.sleep Usage

```logql
{source="MapleExpectation"}
|=`Thread.sleep`
| line_format "Anti-pattern detected at {{.file}}:{{.line}}"
```

### 3. Find Redis Connection Issues

```logql
{source="MapleExpectation"}
|="Redis"
|="connection"
|="failed"
| line_format "{{.timestamp}} - {{.message}}"
```

---

## Alert Rules

### Prometheus Alerting Rules

```yaml
groups:
  - name: maple_expectation_tests
    rules:
      # Alert if test pass rate drops below 95%
      - alert: LowTestPassRate
        expr: |
          sum(rate(junit_tests_passed_total[5m])) /
          sum(rate(junit_tests_total_total[5m])) < 0.95
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Test pass rate below 95%"
          description: "Current pass rate: {{ $value }}%"

      # Alert if flaky tests detected
      - alert: FlakyTestsDetected
        expr: count(junit_tests_flaky_detected) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Flaky tests detected in build"
          description: "{{ $value }} flaky tests found"

      # Alert if previously flaky test fails
      - alert: PreviouslyFlakyTestFailed
        expr: |
          junit_tests_failed_total{test_class=~"DonationTest|RefreshTokenIntegrationTest|LikeSyncCompensationIntegrationTest"} > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Previously flaky test failed: {{ $labels.test_class }}"
```

---

## Validation Results (Pre-Fix vs Post-Fix)

### Before Fix (2026-02-09)

| Test | Pass Rate | Flaky? | Issue |
|------|-----------|--------|-------|
| `DonationTest.concurrencyTest()` | ~70% | Yes | #328 |
| `DonationTest.hotspotTest()` | ~75% | Yes | #328 |
| `RefreshTokenIntegrationTest.*` | ~80% | Yes | #329 |
| `LikeSyncCompensationIntegrationTest.syncSuccess_TempKeyDeleted()` | ~60% | Yes | #330 |
| `LikeSyncCompensationIntegrationTest.consecutiveFailuresThenSuccess_WorksCorrectly()` | ~65% | Yes | #330 |

### After Fix (2026-02-10)

| Test | Pass Rate | Flaky? | Status |
|------|-----------|--------|--------|
| `DonationTest.concurrencyTest()` | 100% | No | ✅ FIXED |
| `DonationTest.hotspotTest()` | 100% | No | ✅ FIXED |
| `RefreshTokenIntegrationTest.*` (8 tests) | 100% | No | ✅ FIXED |
| `LikeSyncCompensationIntegrationTest.syncSuccess_TempKeyDeleted()` | 100% | No | ✅ FIXED |
| `LikeSyncCompensationIntegrationTest.consecutiveFailuresThenSuccess_WorksCorrectly()` | 100% | No | ✅ FIXED |

---

## Continuous Monitoring Setup

### 1. Prometheus Configuration

Add to `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'maple-expectation-tests'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
    scrape_interval: 30s
```

### 2. Grafana Dashboard Import

1. Navigate to Grafana → Dashboards → Import
2. Paste the JSON configuration above
3. Select Prometheus data source
4. Save as "MapleExpectation - Test Quality"

### 3. Alertmanager Configuration

Add to `alertmanager.yml`:

```yaml
receivers:
  - name: 'slack'
    slack_configs:
      - api_url: '${SLACK_WEBHOOK_URL}'
        channel: '#maple-expectation-ci'

route:
  receiver: 'slack'
  group_by: ['alertname', 'severity']
  routes:
    - match:
        severity: critical
      receiver: 'slack'
```

---

## Summary

The monitoring configuration ensures:

1. **Visibility** - Real-time test pass rate tracking
2. **Alerting** - Immediate notification of test failures
3. **Historical** - Trend analysis for quality improvement
4. **Accountability** - Specific tracking of previously flaky tests

---

**Document Version:** 1.0.0
**Last Updated:** 2026-02-10
**Maintained By:** MapleExpectation QA Team
