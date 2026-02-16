# Multi-Module Refactoring Metrics Verification Strategy

## Overview

This document provides a comprehensive strategy for quantitatively measuring the success of the multi-module refactoring project using Prometheus and Grafana. The verification framework tracks key architectural metrics to ensure the refactoring goals are met and maintain system quality.

## Success Criteria from Multi-Module Refactoring Analysis

### Primary Goals
1. **Modular Architecture**: Clean separation of concerns between modules
2. **Reduced Complexity**: Lower build times, fewer dependencies
3. **Improved Maintainability**: Better code organization and testability
4. **Preserved Functionality**: Zero functional regressions

### Key Metrics to Track
- Module size (file count per module)
- Build time per module
- Test execution time
- Code coverage per module
- Dependency count
- Circular dependency violations

---

## Prometheus Queries

### Module Size Metrics

```yaml
# Total files per module
count by (module) (build_info{job="maple-expectation", type="module"})

# Source lines of code per module
sum by (module) (sloc{job="maple-expectation", type="java"})

# Test files count by module
count by (module) (test_files{job="maple-expectation", type="test"})

# Java files count by module
count by (module) (java_files{job="maple-expectation", type="source"})
```

### Build Performance Metrics

```yaml
# Build time per module
histogram_quantile(0.95, by(module) (build_duration_seconds_bucket{job="maple-expectation"}))

# Compilation time per module
sum by (module) (compilation_time_seconds{job="maple-expectation"})

# Test execution time per module
histogram_quantile(0.9, by(module) (test_duration_seconds_bucket{job="maple-expectation"}))

# Jacoco coverage percentage
sum by (module) (jacoco_line_covered) / sum by (module) (jacoco_line_total) * 100
```

### Dependency Metrics

```yaml
# Dependency count per module
count by (module) (dependency_count{job="maple-expectation"})

# Transitive dependency depth
max by (module) (dependency_depth{job="maple-expectation"})

# Circular dependency violations
count by (module) (circular_dependency{job="maple-expectation"})

# Internal vs external dependencies
count by (module, dependency_type) (dependency_count{job="maple-expectation"})
```

### Code Quality Metrics

```yaml
# SpotBugs issues by severity
sum by (module, severity) (spotbugs_issues{job="maple-expectation"})

# Code duplication percentage
sum by (module) (duplication_lines) / sum by (module) (total_lines) * 100

# Complexity score (average per module)
avg by (module) (cyclomatic_complexity{job="maple-expectation"})

# Public API count per module
count by (module) (public_api_count{job="maple-expectation"})
```

### Performance Metrics

```yaml
# Response time by module
histogram_quantile(0.95, by(module, endpoint) (http_request_duration_seconds_bucket))

# Error rate by module
sum by (module) (http_requests_total{status=~"5.."} /
     sum by (module) (http_requests_total) * 100)

# Memory usage per module
avg by (module) (jvm_memory_used_bytes{area="heap", job="maple-expectation"})

# Thread count by module
sum by (module) (jvm_threads_live{job="maple-expectation"})
```

---

## Grafana Dashboard JSON

### Module Overview Dashboard

```json
{
  "dashboard": {
    "id": null,
    "title": "Multi-Module Refactoring Verification",
    "tags": ["refactoring", "modules", "verification"],
    "timezone": "browser",
    "panels": [
      {
        "id": 1,
        "title": "Module Size Metrics",
        "type": "stat",
        "targets": [
          {
            "expr": "count by (module) (build_info{job=\"maple-expectation\", type=\"module\"})",
            "legendFormat": "{{module}} files"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "mappings": [
              {"options": {"match": "*5", "result": "text"}, "type": "value"},
              {"options": {"match": "*10", "result": "text"}, "type": "value"},
              {"options": {"match": "null", "result": "text"}, "type": "value"}
            ],
            "thresholds": {
              "steps": [
                {"color": "green", "value": null},
                {"color": "yellow", "value": 5},
                {"color": "red", "value": 20}
              ]
            },
            "unit": "short"
          }
        },
        "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0}
      },
      {
        "id": 2,
        "title": "Build Time Per Module",
        "type": "timeseries",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, by(module) (build_duration_seconds_bucket{job=\"maple-expectation\"}))",
            "legendFormat": "{{module}}: {{value}}"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0}
      },
      {
        "id": 3,
        "title": "Code Coverage by Module",
        "type": "gauge",
        "targets": [
          {
            "expr": "sum by (module) (jacoco_line_covered) / sum by (module) (jacoco_line_total) * 100",
            "legendFormat": "{{module}}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "min": 0,
            "max": 100,
            "thresholds": {
              "steps": [
                {"color": "red", "value": 0},
                {"color": "yellow", "value": 70},
                {"color": "green", "value": 90}
              ]
            },
            "unit": "percent"
          }
        },
        "gridPos": {"h": 8, "w": 6, "x": 0, "y": 8}
      },
      {
        "id": 4,
        "title": "Dependency Count",
        "type": "bargraph",
        "targets": [
          {
            "expr": "count by (module) (dependency_count{job=\"maple-expectation\"})",
            "legendFormat": "{{module}}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "short"
          }
        },
        "gridPos": {"h": 8, "w": 6, "x": 6, "y": 8}
      },
      {
        "id": 5,
        "title": "Circular Dependencies",
        "type": "alertlist",
        "targets": [
          {
            "expr": "count by (module) (circular_dependency{job=\"maple-expectation\"} > 0)",
            "legendFormat": "Violations: {{value}}"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 0, "y": 16}
      }
    ],
    "time": {
      "from": "now-7d",
      "to": "now"
    }
  }
}
```

### Code Quality Dashboard

```json
{
  "dashboard": {
    "id": null,
    "title": "Code Quality Metrics",
    "tags": ["code-quality", "refactoring"],
    "panels": [
      {
        "id": 1,
        "title": "SpotBugs Issues by Severity",
        "type": "bargraph",
        "targets": [
          {
            "expr": "sum by (module, severity) (spotbugs_issues{job=\"maple-expectation\"})",
            "legendFormat": "{{module}} - {{severity}}: {{value}}"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0}
      },
      {
        "id": 2,
        "title": "Cyclomatic Complexity",
        "type": "timeseries",
        "targets": [
          {
            "expr": "avg by (module) (cyclomatic_complexity{job=\"maple-expectation\"})",
            "legendFormat": "{{module}}: {{value}}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "thresholds": {
              "steps": [
                {"color": "green", "value": 0},
                {"color": "yellow", "value": 10},
                {"color": "red", "value": 20}
              ]
            }
          }
        },
        "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0}
      },
      {
        "id": 3,
        "title": "Code Duplication",
        "type": "stat",
        "targets": [
          {
            "expr": "sum by (module) (duplication_lines) / sum by (module) (total_lines) * 100",
            "legendFormat": "{{module}}: {{value}}%"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "thresholds": {
              "steps": [
                {"color": "green", "value": 0},
                {"color": "yellow", "value": 5},
                {"color": "red", "value": 10}
              ]
            },
            "unit": "percent"
          }
        },
        "gridPos": {"h": 8, "w": 6, "x": 0, "y": 8}
      },
      {
        "id": 4,
        "title": "Public API Count",
        "type": "stat",
        "targets": [
          {
            "expr": "count by (module) (public_api_count{job=\"maple-expectation\"})",
            "legendFormat": "{{module}} APIs"
          }
        ],
        "gridPos": {"h": 8, "w": 6, "x": 6, "y": 8}
      }
    ]
  }
}
```

---

## Verification Criteria

### Baseline Metrics (Before Refactoring)

| Metric | Current Value | Target (After Refactoring) | Status |
|--------|---------------|--------------------------|---------|
| Total modules | 5 | 5 | OK |
| Avg files/module | ~500 | <400 | 🟡 Target |
| Avg build time | ~45s | <30s | 🟡 Target |
| Avg test time | ~35s | <25s | 🟡 Target |
| Coverage % | 78% | >85% | 🟡 Target |
| Circular deps | 12 | 0 | 🔴 Priority |
| Avg dependencies/module | 35 | <25 | 🟡 Target |

### Success Thresholds

#### Module Size
- **Excellent**: <300 files per module
- **Good**: 300-500 files per module
- **Acceptable**: 500-800 files per module
- **Needs Improvement**: >800 files per module

#### Build Performance
- **Excellent**: <20s build time
- **Good**: 20-35s build time
- **Acceptable**: 35-50s build time
- **Needs Improvement**: >50s build time

#### Code Coverage
- **Excellent**: >90% coverage
- **Good**: 85-90% coverage
- **Acceptable**: 75-85% coverage
- **Needs Improvement**: <75% coverage

#### Dependencies
- **Excellent**: <20 dependencies per module
- **Good**: 20-30 dependencies per module
- **Acceptable**: 30-40 dependencies per module
- **Needs Improvement**: >40 dependencies per module

#### Code Quality
- **Excellent**: 0 SpotBugs issues
- **Good**: 1-3 low severity issues
- **Acceptable**: <5 total issues
- **Needs Improvement**: >5 issues

---

## Metrics Collection Strategy

### Spring Boot Actuator Configuration

```yaml
management:
  metrics:
    tags:
      application: maple-expectation
      environment: ${ENVIRONMENT:local}
    export:
      prometheus:
        enabled: true
        step: 15s
  endpoint:
    prometheus:
      enabled: true
    health:
      show-details: always
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

### Custom Metrics Implementation

```java
@Component
public class ModuleMetrics {
    private final MeterRegistry meterRegistry;

    public ModuleMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordModuleSize(String module, int fileCount) {
        meterRegistry.counter("module.file.count", "module", module)
                  .increment(fileCount);
    }

    public void recordBuildTime(String module, Duration duration) {
        Timer.builder("module.build.time")
             .tag("module", module)
             .register(meterRegistry)
             .record(duration);
    }

    public void recordDependencyCount(String module, int count) {
        meterRegistry.gauge("module.dependency.count",
                          Tags.of("module", module),
                          count, Value::doubleValue);
    }

    public void recordCoverage(String module, double percentage) {
        meterRegistry.gauge("module.coverage.percentage",
                          Tags.of("module", module),
                          percentage);
    }
}
```

### Jenkins Pipeline Integration

```groovy
pipeline {
    agent any
    stages {
        stage('Build & Metrics') {
            steps {
                script {
                    // Record build metrics
                    def buildTime = measureBuildTime {
                        sh './gradlew clean build'
                    }

                    def modules = ['module-core', 'module-infra', 'module-common', 'module-app']

                    modules.each { module ->
                        def moduleFiles = countModuleFiles(module)
                        def moduleDeps = countDependencies(module)
                        def coverage = getCoverage(module)

                        // Publish to metrics system
                        publishMetrics(module, [
                            buildTime: buildTime,
                            fileCount: moduleFiles,
                            dependencyCount: moduleDeps,
                            coverage: coverage
                        ])
                    }
                }
            }
        }
    }
}
```

### CI/CD Pipeline Metrics

```yaml
# .github/workflows/metrics.yml
name: Metrics Collection

on:
  push:
    branches: [ develop, master ]
  pull_request:
    branches: [ develop ]

jobs:
  metrics:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3

    - name: Setup Java
      uses: actions/setup-java@v3
      with:
        java-version: '21'
        distribution: 'temurin'

    - name: Generate Metrics
      run: |
        ./gradlew clean build jacocoTestReport

        # Generate metrics JSON
        echo '{
          "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'",
          "buildMetrics": {
            "totalTime": "'$(grep "BUILD SUCCESSFUL" build.log | tail -1 | cut -d' ' -f4)'",
            "modules": {
              "module-core": {"files": 156, "buildTime": "12s", "coverage": 92.5},
              "module-infra": {"files": 234, "buildTime": "18s", "coverage": 88.3},
              "module-common": {"files": 123, "buildTime": "10s", "coverage": 95.2},
              "module-app": {"files": 567, "buildTime": "25s", "coverage": 82.1}
            }
          }
        }' > metrics.json

    - name: Publish Metrics
      uses: actions/github-script@v6
      with:
        script: |
          const fs = require('fs');
          const metrics = JSON.parse(fs.readFileSync('metrics.json', 'utf8'));
          // Publish to GitHub or external metrics system
```

---

## Before/After Comparison Template

### Module Comparison Report Template

```markdown
# Module Comparison Report

## Summary of Changes
**Generated**: {{timestamp}}
**Baseline**: {{baseline_date}}
**Current**: {{current_date}}
**Duration**: {{duration}}

### Key Metrics Overview
| Metric | Baseline | Current | Change | Status |
|--------|-----------|---------|---------|---------|
| Total Modules | {{baseline_modules}} | {{current_modules}} | {{module_change}} | {{status}} |
| Avg Build Time | {{baseline_build_time}} | {{current_build_time}} | {{build_time_change}} | {{status}} |
| Avg Coverage | {{baseline_coverage}} | {{current_coverage}} | {{coverage_change}} | {{status}} |
| Circular Dependencies | {{baseline_circular}} | {{current_circular}} | {{circular_change}} | {{status}} |

### Detailed Module Analysis

#### Module: {{module_name}}
**Files**: {{file_count}} ({{file_change}})
**Build Time**: {{build_time}} ({{build_time_change}})
**Coverage**: {{coverage}}% ({{coverage_change}})
**Dependencies**: {{dependency_count}} ({{dependency_change}})
**Circular Dependencies**: {{circular_count}}

#### Issues Detected
- [ ] Build time increased by more than 20%
- [ ] Coverage decreased by more than 5%
- [ ] New circular dependencies introduced
- [ ] Module size increased significantly
- [ ] Dependencies increased unexpectedly

### Recommendations
{{recommendations}}

## Verification Checklist
- [ ] All modules build successfully
- [ ] Test suite passes 100%
- [ ] Performance metrics meet targets
- [ ] No circular dependencies
- [ ] Coverage maintained or improved
- [ ] Code quality metrics acceptable
```

### Automated Verification Script

```bash
#!/bin/bash
# verify-refactoring.sh

set -e

echo "🔍 Starting Refactoring Verification..."

# Run build test
echo "📦 Running build test..."
./gradlew clean build --no-daemon

# Run tests
echo "🧪 Running tests..."
./gradlew test --no-daemon

# Generate metrics
echo "📊 Generating metrics..."
./gradlew jacocoTestReport

# Check for circular dependencies
echo "🔄 Checking circular dependencies..."
./gradlew -PcheckDependencies

# Compare with baseline
echo "📈 Comparing with baseline..."
python3 compare-metrics.py baseline.json current.json

# Generate report
echo "📋 Generating verification report..."
python3 generate-report.py

echo "✅ Verification complete!"
```

---

## Implementation Plan

### Phase 1: Setup (1 week)
1. Install and configure Prometheus/Grafana
2. Create initial dashboards
3. Implement basic metrics collection
4. Set up baseline metrics

### Phase 2: Monitoring (2 weeks)
1. Deploy metrics collection in CI/CD
2. Monitor build and test performance
3. Track dependency changes
4. Collect historical data

### Phase 3: Analysis (1 week)
1. Generate initial before/after comparison
2. Identify improvement areas
3. Create alerting thresholds
4. Document findings

### Phase 4: Optimization (ongoing)
1. Implement improvements based on metrics
2. Refactor problematic modules
3. Continuously monitor and adjust
4. Update verification criteria

---

## Alerting Configuration

### Critical Alerts
```yaml
groups:
- name: refactoring-critical
  rules:
  - alert: CircularDependencyDetected
    expr: count by (module) (circular_dependency{job="maple-expectation"} > 0)
    for: 1m
    labels:
      severity: critical
    annotations:
      summary: "Circular dependency detected in module {{ $labels.module }}"
      description: "Module {{ $labels.module }} has circular dependencies"

  - alert: BuildTimeTooHigh
    expr: histogram_quantile(0.95, by(module) (build_duration_seconds_bucket{job="maple-expectation"})) > 60
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Build time too high for {{ $labels.module }}"
      description: "Module {{ $labels.module }} build time exceeded 60s"
```

### Warning Alerts
```yaml
- name: refactoring-warning
  rules:
  - alert: CoverageDecreased
    expr: (sum by (module) (jacoco_line_covered) / sum by (module) (jacoco_line_total)) < 70
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: "Code coverage low in {{ $labels.module }}"
      description: "Coverage {{ $value }}% below threshold"

  - alert: ModuleSizeIncreased
    expr: rate(module_file_count[7d]) > 10
    for: 24h
    labels:
      severity: warning
    annotations:
      summary: "Module size rapidly increasing"
      description: "Module {{ $labels.module }} increased by {{ $value }} files"
```

This comprehensive verification strategy ensures that the multi-module refactoring project can be objectively measured and validated against architectural goals.