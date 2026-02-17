# Metrics Collection Implementation Plan

## Overview

This document provides a practical implementation plan for the multi-module refactoring metrics verification strategy documented in [docs/metrics/verification-strategy.md](../metrics/verification-strategy.md).

**Goal:** Implement automated metrics collection to quantitatively measure refactoring success and validate architectural improvements.

**Current State:**
- ✅ Micrometer + Prometheus already configured
- ✅ Actuator endpoints enabled
- ✅ Existing metrics collectors (JVM, Database, Redis, CircuitBreaker)
- ✅ 625 Java files across 5 modules (module-core, module-infra, module-common, module-app, module-chaos-test)

**Implementation Scope:**
1. Custom metrics collectors for module-level metrics
2. Gradle build lifecycle integration
3. Prometheus Push Gateway integration
4. Grafana dashboards for before/after comparison
5. Automated verification scripts

---

## Phase 1: Custom Metrics Collectors (2-3 hours)

### 1.1 ModuleSizeMetricsCollector

**Purpose:** Track file counts and lines of code per module.

**Location:** `module-app/src/main/java/maple/expectation/monitoring/collector/ModuleSizeMetricsCollector.java`

**Metrics to Emit:**
- `module.file.count` - Total Java files per module
- `module.sloc.total` - Source lines of code per module
- `module.test.file.count` - Test files per module
- `module.public.api.count` - Public API methods per module

**Implementation:**
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ModuleSizeMetricsCollector implements MetricsCollectorStrategy {

    private final MeterRegistry meterRegistry;
    private final ApplicationContext applicationContext;

    @Override
    public String getCategoryName() {
        return MetricCategory.MODULE_SIZE.getKey();
    }

    @Override
    public Map<String, Object> collect() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        String[] modules = {"module-core", "module-infra", "module-common", "module-app"};

        for (String module : modules) {
            try {
                Path modulePath = Paths.get(".").resolve(module);
                int javaFiles = countJavaFiles(modulePath);
                int testFiles = countTestFiles(modulePath);
                int sloc = countSLOC(modulePath);
                int publicApi = countPublicAPIs(module);

                // Record to Micrometer
                Tags moduleTags = Tags.of("module", module.replace("module-", ""));

                meterRegistry.gauge("module.file.count", moduleTags, javaFiles);
                meterRegistry.gauge("module.sloc.total", moduleTags, sloc);
                meterRegistry.gauge("module.test.file.count", moduleTags, testFiles);
                meterRegistry.gauge("module.public.api.count", moduleTags, publicApi);

                metrics.put(module + "_files", javaFiles);
                metrics.put(module + "_sloc", sloc);
                metrics.put(module + "_tests", testFiles);
                metrics.put(module + "_public_api", publicApi);

            } catch (Exception e) {
                log.warn("Failed to collect metrics for module: {}", module, e);
            }
        }

        return metrics;
    }

    private int countJavaFiles(Path modulePath) throws IOException {
        try (var stream = Files.walk(modulePath.resolve("src/main/java"))) {
            return (int) stream
                .filter(p -> p.toString().endsWith(".java"))
                .count();
        }
    }

    private int countTestFiles(Path modulePath) throws IOException {
        Path testPath = modulePath.resolve("src/test/java");
        if (!Files.exists(testPath)) return 0;

        try (var stream = Files.walk(testPath)) {
            return (int) stream
                .filter(p -> p.toString().endsWith(".java"))
                .count();
        }
    }

    private int countSLOC(Path modulePath) throws IOException {
        try (var stream = Files.walk(modulePath.resolve("src/main/java"))) {
            return stream
                .filter(p -> p.toString().endsWith(".java"))
                .mapToInt(this::countLinesInFile)
                .sum();
        }
    }

    private int countLinesInFile(Path file) {
        try {
            return (int) Files.lines(file)
                .filter(line -> !line.trim().isEmpty())
                .filter(line -> !line.trim().startsWith("//"))
                .filter(line -> !line.trim().startsWith("/*"))
                .filter(line -> !line.trim().startsWith("*"))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private int countPublicAPIs(Path modulePath) throws IOException {
        try (var stream = Files.walk(modulePath.resolve("src/main/java"))) {
            return stream
                .filter(p -> p.toString().endsWith(".java"))
                .mapToInt(this::countPublicMethodsInFile)
                .sum();
        }
    }

    private int countPublicMethodsInFile(Path file) {
        try {
            String content = Files.readString(file);
            // Simple regex for public methods (not perfect but practical)
            Pattern pattern = Pattern.compile("public\\s+\\w+\\s+\\w+\\s*\\(");
            Matcher matcher = pattern.matcher(content);
            int count = 0;
            while (matcher.find()) count++;
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public boolean supports(MetricCategory category) {
        return MetricCategory.MODULE_SIZE == category;
    }

    @Override
    public int getOrder() {
        return 1; // Collect first (fast, I/O bound)
    }
}
```

**Integration Point:** Add to existing `MetricsCollectorStrategy` registry in `config/MetricsConfig.java`.

---

### 1.2 DependencyViolationMetricsCollector

**Purpose:** Track architectural violations and circular dependencies.

**Location:** `module-app/src/main/java/maple/expectation/monitoring/collector/DependencyViolationMetricsCollector.java`

**Metrics to Emit:**
- `module.dependency.count` - Total dependencies per module
- `module.dependency.internal` - Internal module dependencies
- `module.dependency.external` - External library dependencies
- `module.circular.violations` - Circular dependency violations

**Implementation:**
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DependencyViolationMetricsCollector implements MetricsCollectorStrategy {

    private final MeterRegistry meterRegistry;
    private final ProjectObjectModel projectModel;

    @Override
    public String getCategoryName() {
        return MetricCategory.DEPENDENCY.getKey();
    }

    @Override
    public Map<String, Object> collect() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        String[] modules = {"module-core", "module-infra", "module-common", "module-app"};

        for (String module : modules) {
            try {
                var gradleModule = projectModel.findModule(module);
                if (gradleModule.isEmpty()) continue;

                var dependencies = gradleModule.get().getDependencies();
                int internalDeps = (int) dependencies.stream()
                    .filter(d -> d.getProject() != null)
                    .count();
                int externalDeps = dependencies.size() - internalDeps;

                // Check for circular dependencies
                int circularViolations = detectCircularDependencies(module);

                Tags moduleTags = Tags.of("module", module.replace("module-", ""));

                meterRegistry.gauge("module.dependency.count", moduleTags, dependencies.size());
                meterRegistry.gauge("module.dependency.internal", moduleTags, internalDeps);
                meterRegistry.gauge("module.dependency.external", moduleTags, externalDeps);
                meterRegistry.gauge("module.circular.violations", moduleTags, circularViolations);

                metrics.put(module + "_total_deps", dependencies.size());
                metrics.put(module + "_internal_deps", internalDeps);
                metrics.put(module + "_external_deps", externalDeps);
                metrics.put(module + "_circular_violations", circularViolations);

            } catch (Exception e) {
                log.warn("Failed to collect dependency metrics for module: {}", module, e);
            }
        }

        return metrics;
    }

    private int detectCircularDependencies(String moduleName) {
        // Use existing Gradle dependency insight report
        // Or implement simple cycle detection using dependency graph
        try {
            var process = new ProcessBuilder(
                "./gradlew",
                ":module-core:dependencyInsight",
                "--dependency",
                moduleName.replace("module-", ""),
                "--console",
                "plain"
            ).start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            String line;
            int violations = 0;
            boolean inCircularSection = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("circular")) {
                    inCircularSection = true;
                }
                if (inCircularSection && line.contains("ERROR")) {
                    violations++;
                }
            }

            process.waitFor();
            return violations;

        } catch (Exception e) {
            log.warn("Failed to detect circular dependencies for: {}", moduleName, e);
            return 0;
        }
    }

    @Override
    public boolean supports(MetricCategory category) {
        return MetricCategory.DEPENDENCY == category;
    }

    @Override
    public int getOrder() {
        return 3;
    }
}
```

**Integration Point:** Requires Gradle Tooling API to parse module dependencies.

---

### 1.3 BuildPerformanceMetricsCollector

**Purpose:** Track build and test execution times per module.

**Location:** `module-app/src/main/java/maple/expectation/monitoring/collector/BuildPerformanceMetricsCollector.java`

**Metrics to Emit:**
- `module.build.duration` - Build time per module (Histogram)
- `module.test.duration` - Test execution time per module (Histogram)
- `module.jacoco.coverage` - Code coverage percentage per module (Gauge)

**Implementation:**
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildPerformanceMetricsCollector implements MetricsCollectorStrategy {

    private final MeterRegistry meterRegistry;

    @Override
    public String getCategoryName() {
        return MetricCategory.BUILD_PERFORMANCE.getKey();
    }

    @Override
    public Map<String, Object> collect() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // Read from build report generated by Gradle
        Path buildReportPath = Paths.get("build/reports/build-performance.json");

        if (!Files.exists(buildReportPath)) {
            log.warn("Build performance report not found at: {}", buildReportPath);
            return metrics;
        }

        try {
            String json = Files.readString(buildReportPath);
            JSONObject report = new JSONObject(json);

            JSONArray modules = report.getJSONArray("modules");

            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.getJSONObject(i);
                String moduleName = module.getString("name");
                double buildTime = module.getDouble("buildTime");
                double testTime = module.getDouble("testTime");
                double coverage = module.getDouble("coverage");

                Tags moduleTags = Tags.of("module", moduleName.replace("module-", ""));

                // Record build time histogram
                Timer.builder("module.build.duration")
                    .tags(moduleTags)
                    .register(meterRegistry)
                    .record(Duration.ofMillis((long) buildTime));

                // Record test time histogram
                Timer.builder("module.test.duration")
                    .tags(moduleTags)
                    .register(meterRegistry)
                    .record(Duration.ofMillis((long) testTime));

                // Record coverage gauge
                meterRegistry.gauge("module.jacoco.coverage", moduleTags, coverage);

                metrics.put(moduleName + "_build_ms", buildTime);
                metrics.put(moduleName + "_test_ms", testTime);
                metrics.put(moduleName + "_coverage", coverage);
            }

        } catch (Exception e) {
            log.warn("Failed to parse build performance report", e);
        }

        return metrics;
    }

    @Override
    public boolean supports(MetricCategory category) {
        return MetricCategory.BUILD_PERFORMANCE == category;
    }

    @Override
    public int getOrder() {
        return 4;
    }
}
```

**Integration Point:** Requires Gradle task to generate build performance JSON (see Phase 2).

---

## Phase 2: Gradle Integration (1-2 hours)

### 2.1 Build Performance Reporter Task

**Location:** `build.gradle` (root)

**Purpose:** Generate JSON report with build/test times per module.

```gradle
// Add to root build.gradle
tasks.register('generateBuildPerformanceReport') {
    group = 'reporting'
    description = 'Generate build performance metrics JSON'

    doLast {
        def modules = ['module-core', 'module-infra', 'module-common', 'module-app']
        def report = [:]
        report.timestamp = Instant.now().toString()
        report.modules = []

        modules.each { moduleName ->
            def startTime = System.currentTimeMillis()

            // Measure build time
            def buildStart = System.currentTimeMillis()
            exec {
                commandLine './gradlew', ":${moduleName}:build", '-x', 'test'
                ignoreExitValue = true
            }
            def buildTime = System.currentTimeMillis() - buildStart

            // Measure test time
            def testStart = System.currentTimeMillis()
            exec {
                commandLine './gradlew', ":${moduleName}:test"
                ignoreExitValue = true
            }
            def testTime = System.currentTimeMillis() - testStart

            // Parse Jacoco coverage
            def jacocoXml = file("${moduleName}/build/reports/jacoco/test/jacocoTestReport.xml")
            def coverage = 0.0
            if (jacocoXml.exists()) {
                def xml = new XmlSlurper().parse(jacocoXml)
                def covered = xml.counter.find { it.@type == 'LINE' }.@covered.toInteger()
                def missed = xml.counter.find { it.@type == 'LINE' }.@missed.toInteger()
                coverage = (covered / (covered + missed)) * 100
            }

            report.modules << [
                name: moduleName,
                buildTime: buildTime,
                testTime: testTime,
                coverage: coverage
            ]
        }

        // Write JSON report
        def jsonOutput = new groovy.json.JsonBuilder(report).toPrettyString()
        file('build/reports/build-performance.json').parentFile.mkdirs()
        file('build/reports/build-performance.json').text = jsonOutput

        println "✅ Build performance report generated: build/reports/build-performance.json"
    }
}
```

**Usage:**
```bash
./gradlew generateBuildPerformanceReport
```

---

### 2.2 Prometheus Push Gateway Integration

**Location:** `build.gradle` (root)

**Purpose:** Push metrics to Prometheus Push Gateway after each build.

```gradle
// Add dependencies
buildscript {
    dependencies {
        classpath 'io.prometheus:prometheus_pushgateway:0.12.0'
    }
}

// Create task to push metrics
tasks.register('pushMetricsToPrometheus') {
    group = 'monitoring'
    description = 'Push build metrics to Prometheus Push Gateway'

    doLast {
        def pushgatewayUrl = System.getenv('PROMETHEUS_PUSHGATEWAY_URL') ?: 'http://localhost:9091'
        def jobName = 'maple-expectation-build'

        // Read build performance report
        def reportFile = file('build/reports/build-performance.json')
        if (!reportFile.exists()) {
            println "⚠️ Build performance report not found. Run generateBuildPerformanceReport first."
            return
        }

        def report = new groovy.json.JsonSlurper().parse(reportFile)

        // Push metrics using curl (simpler than Java client for Gradle)
        report.modules.each { module ->
            def moduleName = module.name.replace('module-', '')

            // Build time
            exec {
                commandLine 'curl',
                    '-X', 'POST',
                    "${pushgatewayUrl}/metrics/job/${jobName}/module/${moduleName}",
                    '-d', "module_build_duration_seconds{module=\"${moduleName}\"} ${module.buildTime / 1000.0}"
                ignoreExitValue = true
            }

            // Test time
            exec {
                commandLine 'curl',
                    '-X', 'POST',
                    "${pushgatewayUrl}/metrics/job/${jobName}/module/${moduleName}",
                    '-d', "module_test_duration_seconds{module=\"${moduleName}\"} ${module.testTime / 1000.0}"
                ignoreExitValue = true
            }

            // Coverage
            exec {
                commandLine 'curl',
                    '-X', 'POST',
                    "${pushgatewayUrl}/metrics/job/${jobName}/module/${moduleName}",
                    '-d', "module_coverage_percent{module=\"${moduleName}\"} ${module.coverage}"
                ignoreExitValue = true
            }
        }

        println "✅ Metrics pushed to Prometheus Push Gateway: ${pushgatewayUrl}"
    }
}
```

**Usage:**
```bash
export PROMETHEUS_PUSHGATEWAY_URL=http://localhost:9091
./gradlew pushMetricsToPrometheus
```

---

### 2.3 CI/CD Pipeline Integration

**Location:** `.github/workflows/metrics.yml`

**Purpose:** Collect and publish metrics on every CI build.

```yaml
name: Metrics Collection

on:
  push:
    branches: [develop, master]
  pull_request:
    branches: [develop]

jobs:
  collect-metrics:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Generate build performance report
        run: ./gradlew generateBuildPerformanceReport

      - name: Upload metrics artifact
        uses: actions/upload-artifact@v4
        with:
          name: build-metrics
          path: build/reports/build-performance.json
          retention-days: 30

      - name: Comment PR with metrics
        if: github.event_name == 'pull_request'
        uses: actions/github-script@v7
        with:
          script: |
            const fs = require('fs');
            const metrics = JSON.parse(fs.readFileSync('build/reports/build-performance.json', 'utf8'));

            let comment = '## 📊 Build Metrics\n\n';
            comment += '| Module | Build Time | Test Time | Coverage |\n';
            comment += '|--------|------------|-----------|----------|\n';

            metrics.modules.forEach(m => {
              const shortName = m.name.replace('module-', '');
              comment += `| ${shortName} | ${m.buildTime}ms | ${m.testTime}ms | ${m.coverage.toFixed(1)}% |\n`;
            });

            github.rest.issues.createComment({
              issue_number: context.issue.number,
              owner: context.repo.owner,
              repo: context.repo.repo,
              body: comment
            });
```

---

## Phase 3: Dashboard Creation (2-3 hours)

### 3.1 Before Refactoring Dashboard

**Location:** `docs/metrics/dashboards/before-refactoring.json`

**Purpose:** Establish baseline metrics before refactoring begins.

```json
{
  "dashboard": {
    "title": "Multi-Module Refactoring - Before Baseline",
    "tags": ["refactoring", "baseline", "before"],
    "timezone": "browser",
    "refresh": "30s",
    "panels": [
      {
        "id": 1,
        "title": "Module Size (Files)",
        "type": "stat",
        "targets": [
          {
            "expr": "module_file_count{job=\"maple-expectation\"}",
            "legendFormat": "{{module}}",
            "format": "table",
            "instant": true
          }
        ],
        "transformations": [
          {
            "id": "organize",
            "options": {
              "excludeByName": {},
              "indexByName": {},
              "renameByName": {
                "Value": "File Count",
                "module": "Module"
              }
            }
          }
        ],
        "fieldConfig": {
          "defaults": {
            "color": {"mode": "palette-classic"},
            "thresholds": {
              "steps": [
                {"color": "green", "value": null},
                {"color": "yellow", "value": 300},
                {"color": "red", "value": 500}
              ]
            }
          }
        },
        "gridPos": {"h": 8, "w": 6, "x": 0, "y": 0}
      },
      {
        "id": 2,
        "title": "Build Time Per Module",
        "type": "timeseries",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(module_build_duration_seconds_bucket{job=\"maple-expectation\"}[5m])) by (module, le))",
            "legendFormat": "{{module}} (p95)"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "s",
            "thresholds": {
              "steps": [
                {"color": "green", "value": null},
                {"color": "yellow", "value": 20},
                {"color": "red", "value": 40}
              ]
            }
          }
        },
        "gridPos": {"h": 8, "w": 12, "x": 6, "y": 0}
      },
      {
        "id": 3,
        "title": "Test Coverage %",
        "type": "gauge",
        "targets": [
          {
            "expr": "module_coverage_percent{job=\"maple-expectation\"}",
            "legendFormat": "{{module}}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "min": 0,
            "max": 100,
            "unit": "percent",
            "thresholds": {
              "steps": [
                {"color": "red", "value": 0},
                {"color": "yellow", "value": 70},
                {"color": "green", "value": 85}
              ]
            }
          }
        },
        "gridPos": {"h": 8, "w": 6, "x": 0, "y": 8}
      },
      {
        "id": 4,
        "title": "Circular Dependency Violations",
        "type": "stat",
        "targets": [
          {
            "expr": "sum(module_circular_violations{job=\"maple-expectation\"})",
            "legendFormat": "Total Violations"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "thresholds": {
              "steps": [
                {"color": "green", "value": null},
                {"color": "red", "value": 1}
              ]
            },
            "mappings": [
              {"type": "value", "options": {"0": {"text": "✅ None", "color": "green"}}}
            ]
          }
        },
        "gridPos": {"h": 8, "w": 6, "x": 6, "y": 8}
      },
      {
        "id": 5,
        "title": "Dependency Count (Internal vs External)",
        "type": "piechart",
        "targets": [
          {
            "expr": "sum by (module) (module_dependency_internal{job=\"maple-expectation\"})",
            "legendFormat": "{{module}} Internal"
          },
          {
            "expr": "sum by (module) (module_dependency_external{job=\"maple-expectation\"})",
            "legendFormat": "{{module}} External"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 0, "y": 16}
      },
      {
        "id": 6,
        "title": "Source Lines of Code (SLOC)",
        "type": "bargauge",
        "targets": [
          {
            "expr": "module_sloc_total{job=\"maple-expectation\"}",
            "legendFormat": "{{module}}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "short",
            "thresholds": {
              "mode": "absolute",
              "steps": [
                {"color": "green", "value": null},
                {"color": "yellow", "value": 10000},
                {"color": "red", "value": 20000}
              ]
            }
          }
        },
        "gridPos": {"h": 8, "w": 12, "x": 12, "y": 16}
      }
    ],
    "time": {
      "from": "now-7d",
      "to": "now"
    }
  }
}
```

---

### 3.2 After Refactoring Dashboard

**Location:** `docs/metrics/dashboards/after-refactoring.json`

**Purpose:** Compare metrics after refactoring to validate improvements.

**Same structure as "before" dashboard with these additions:**

**Comparison Panels (Add to dashboard):**

```json
{
  "id": 10,
  "title": "Improvement Summary (Before vs After)",
  "type": "stat",
  "targets": [
    {
      "expr": "(module_file_count{job=\"maple-expectation\"} offset 7d) - module_file_count{job=\"maple-expectation\"}",
      "legendFormat": "File Count Change"
    }
  ],
  "fieldConfig": {
    "defaults": {
      "mappings": [
        {"type": "value", "options": {"0": {"text": "No Change", "color": "gray"}}},
        {"type": "range", "options": {"from": 1, "to": 100, "result": {"text": "Increased ⚠️", "color": "red"}}},
        {"type": "range", "options": {"from": -100, "to": -1, "result": {"text": "Reduced ✅", "color": "green"}}}
      ],
      "unit": "short"
    }
  },
  "gridPos": {"h": 8, "w": 12, "x": 0, "y": 24}
},
{
  "id": 11,
  "title": "Build Time Improvement %",
  "type": "gauge",
  "targets": [
    {
      "expr": "((histogram_quantile(0.95, sum(rate(module_build_duration_seconds_bucket{job=\"maple-expectation\"}[5m])) by (module, le)) offset 7d) - histogram_quantile(0.95, sum(rate(module_build_duration_seconds_bucket{job=\"maple-expectation\"}[5m])) by (module, le))) / (histogram_quantile(0.95, sum(rate(module_build_duration_seconds_bucket{job=\"maple-expectation\"}[5m])) by (module, le)) offset 7d) * 100",
      "legendFormat": "{{module}}"
    }
  ],
  "fieldConfig": {
    "defaults": {
      "unit": "percent",
      "max": 100,
      "min": -100,
      "thresholds": {
        "steps": [
          {"color": "green", "value": -100},
          {"color": "yellow", "value": 0},
          {"color": "red", "value": 20}
        ]
      }
    }
  },
  "gridPos": {"h": 8, "w": 12, "x": 12, "y": 24}
}
```

---

## Phase 4: Verification & Testing (1 hour)

### 4.1 Automated Verification Script

**Location:** `scripts/verify-metrics.sh`

**Purpose:** Run after refactoring to validate improvements.

```bash
#!/bin/bash
set -e

echo "🔍 Multi-Module Refactoring Metrics Verification"
echo "================================================="

# Configuration
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
BASELINE_DATE="${1:-7d}" # Compare against metrics from 7 days ago

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

check_metric() {
  local metric_name=$1
  local current_query=$2
  local baseline_query=$3
  local threshold=$4 # Acceptable regression threshold (positive = OK)

  echo -n "Checking ${metric_name}... "

  local current=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=${current_query}" | jq -r '.data.result[0].value[1]')
  local baseline=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=${baseline_query}" | jq -r '.data.result[0].value[1]')

  if [[ -z "$current" || -z "$baseline" ]]; then
    echo -e "${YELLOW}⚠️  No data available${NC}"
    return 1
  fi

  local change=$(echo "$current - $baseline" | bc)
  local pct_change=$(echo "scale=1; ($change / $baseline) * 100" | bc)

  # Check if change exceeds threshold
  if (( $(echo "$change < 0" | bc -l) )); then
    # Improvement
    echo -e "${GREEN}✅ Improved by ${pct_change}%${NC}"
  elif (( $(echo "$change <= $threshold" | bc -l) )); then
    # Within acceptable range
    echo -e "${GREEN}✅ Within range (${pct_change}% change)${NC}"
  else
    # Regression
    echo -e "${RED}❌ Regressed by ${pct_change}%${NC}"
    return 1
  fi
}

echo ""
echo "📦 Module Size Metrics"
echo "---------------------"

check_metric \
  "Average Module File Count" \
  "avg(module_file_count)" \
  "avg(module_file_count offset ${BASELINE_DATE})" \
  50

check_metric \
  "Average SLOC per Module" \
  "avg(module_sloc_total)" \
  "avg(module_sloc_total offset ${BASELINE_DATE})" \
  5000

echo ""
echo "⚡ Build Performance Metrics"
echo "----------------------------"

check_metric \
  "95th Percentile Build Time" \
  "histogram_quantile(0.95, sum(rate(module_build_duration_seconds_bucket[5m])) by (le))" \
  "histogram_quantile(0.95, sum(rate(module_build_duration_seconds_bucket offset ${BASELINE_DATE}[5m])) by (le))" \
  10

check_metric \
  "95th Percentile Test Time" \
  "histogram_quantile(0.95, sum(rate(module_test_duration_seconds_bucket[5m])) by (le))" \
  "histogram_quantile(0.95, sum(rate(module_test_duration_seconds_bucket offset ${BASELINE_DATE}[5m])) by (le))" \
  5

echo ""
echo "✅ Code Quality Metrics"
echo "----------------------"

check_metric \
  "Average Test Coverage" \
  "avg(module_coverage_percent)" \
  "avg(module_coverage_percent offset ${BASELINE_DATE})" \
  -5 # Negative threshold means improvement

check_metric \
  "Circular Dependency Violations" \
  "sum(module_circular_violations)" \
  "sum(module_circular_violations offset ${BASELINE_DATE})" \
  0

echo ""
echo "🔗 Dependency Metrics"
echo "---------------------"

check_metric \
  "Average Dependency Count" \
  "avg(module_dependency_count)" \
  "avg(module_dependency_count offset ${BASELINE_DATE})" \
  10

echo ""
echo "================================================="
echo "✅ Verification Complete!"
```

**Usage:**
```bash
chmod +x scripts/verify-metrics.sh

# Compare against 7 days ago
./scripts/verify-metrics.sh 7d

# Compare against custom baseline
./scripts/verify-metrics.sh 14d
```

---

### 4.2 Metrics Collection Test

**Location:** `module-app/src/test/java/maple/expectation/monitoring/ModuleMetricsCollectorTest.java`

**Purpose:** Ensure metrics collectors work correctly.

```java
@ExtendWith(MockitoExtension.class)
class ModuleMetricsCollectorTest {

    @Mock
    private MeterRegistry meterRegistry;

    @Test
    void testModuleSizeMetricsCollector() {
        // Given
        ModuleSizeMetricsCollector collector = new ModuleSizeMetricsCollector(meterRegistry, null);

        // When
        Map<String, Object> metrics = collector.collect();

        // Then
        assertThat(metrics).isNotEmpty();
        assertThat(metrics).containsKey("module-core_files");
        assertThat(metrics).containsKey("module-app_sloc");

        // Verify metrics were registered
        verify(meterRegistry).gauge(eq("module.file.count"), any(Tags.class), anyInt());
    }

    @Test
    void testDependencyViolationMetricsCollector() {
        // Given
        DependencyViolationMetricsCollector collector = new DependencyViolationMetricsCollector(meterRegistry, null);

        // When
        Map<String, Object> metrics = collector.collect();

        // Then
        assertThat(metrics).isNotEmpty();
        assertThat(metrics).containsKey("module-core_total_deps");
        assertThat(metrics).containsKey("module-core_circular_violations");

        // Verify zero circular dependencies (goal)
        int circularViolations = (int) metrics.get("module-core_circular_violations");
        assertThat(circularViolations).isZero();
    }

    @Test
    void testBuildPerformanceMetricsCollector() throws Exception {
        // Given
        BuildPerformanceMetricsCollector collector = new BuildPerformanceMetricsCollector(meterRegistry);

        // Create mock build report
        Path reportPath = Paths.get("build/reports/build-performance.json");
        Files.createDirectories(reportPath.getParent());

        String mockReport = """
            {
              "timestamp": "2026-02-16T10:00:00Z",
              "modules": [
                {
                  "name": "module-core",
                  "buildTime": 12000,
                  "testTime": 8000,
                  "coverage": 92.5
                }
              ]
            }
            """;
        Files.writeString(reportPath, mockReport);

        // When
        Map<String, Object> metrics = collector.collect();

        // Then
        assertThat(metrics).isNotEmpty();
        assertThat(metrics).containsKey("module-core_build_ms");
        assertThat(metrics).containsKey("module-core_test_ms");
        assertThat(metrics).containsKey("module-core_coverage");

        // Verify metrics were registered
        verify(meterRegistry, atLeastOnce()).timerBuilder(anyString());
    }
}
```

---

## Verification Checklist

Before declaring the metrics implementation complete, verify:

- [ ] **ModuleSizeMetricsCollector**
  - [ ] Counts Java files per module accurately
  - [ ] Counts test files per module accurately
  - [ ] Counts SLOC (excluding comments/blank lines)
  - [ ] Counts public API methods per module
  - [ ] Metrics registered in Micrometer registry
  - [ ] Unit tests pass

- [ ] **DependencyViolationMetricsCollector**
  - [ ] Counts total dependencies per module
  - [ ] Separates internal vs external dependencies
  - [ ] Detects circular dependencies using Gradle
  - [ ] Metrics registered in Micrometer registry
  - [ ] Unit tests pass

- [ ] **BuildPerformanceMetricsCollector**
  - [ ] Reads build performance JSON report
  - [ ] Records build time histogram
  - [ ] Records test time histogram
  - [ ] Records coverage gauge
  - [ ] Unit tests pass

- [ ] **Gradle Integration**
  - [ ] `generateBuildPerformanceReport` task works
  - [ ] `pushMetricsToPrometheus` task works
  - [ ] GitHub Actions workflow creates PR comments
  - [ ] Metrics JSON artifact uploaded correctly

- [ ] **Grafana Dashboards**
  - [ ] "Before Refactoring" dashboard imports successfully
  - [ ] "After Refactoring" dashboard imports successfully
  - [ ] All panels display data correctly
  - [ ] Comparison panels show deltas

- [ ] **Verification Script**
  - [ ] `verify-metrics.sh` is executable
  - [ ] Compares against baseline correctly
  - [ ] Detects regressions (red)
  - [ ] Detects improvements (green)
  - [ ] Provides clear output

---

## Success Criteria

The implementation is considered successful when:

1. **All custom metrics collectors are implemented and tested**
   - Unit test coverage > 80% for collectors
   - Metrics successfully registered in Micrometer registry
   - No errors in application logs during startup

2. **Gradle integration works**
   - Build performance report generates correctly
   - Metrics push to Prometheus Push Gateway succeeds
   - GitHub Actions workflow posts metrics on PRs

3. **Grafana dashboards visualize data**
   - All panels display real data (no "No Data" errors)
   - Before/after comparison panels calculate deltas correctly
   - Dashboards auto-refresh every 30s

4. **Verification script validates improvements**
   - Script runs without errors
   - Correctly identifies regressions vs improvements
   - Provides actionable feedback

5. **Baseline metrics established**
   - At least 7 days of historical data collected
   - Dashboard shows "Before Refactoring" baseline
   - Team agrees on target thresholds

---

## Troubleshooting

### Issue: Metrics not appearing in Prometheus

**Symptoms:** Grafana panels show "No Data"

**Diagnosis:**
1. Check Actuator endpoint: `curl http://localhost:8080/actuator/prometheus`
2. Verify custom metrics are listed
3. Check Prometheus targets: `http://localhost:9090/targets`

**Solution:**
- Ensure application is running
- Check `application.yml` management endpoints configuration
- Verify Prometheus scrape interval (should be < 30s)

---

### Issue: Build performance report not generated

**Symptoms:** `build/reports/build-performance.json` doesn't exist

**Diagnosis:**
1. Run `./gradlew generateBuildPerformanceReport --info`
2. Check for permission issues
3. Verify Gradle daemon isn't caching stale results

**Solution:**
```bash
./gradlew --stop
./gradlew clean generateBuildPerformanceReport --no-daemon
```

---

### Issue: Circular dependency detection fails

**Symptoms:** `detectCircularDependencies()` returns 0 even when violations exist

**Diagnosis:**
1. Manually run: `./gradlew :module-core:dependencyInsight --dependency module-infra`
2. Check output for "circular" keyword
3. Verify Gradle version supports dependency analysis

**Solution:**
- Use Gradle's `dryRun` task to detect cycles
- Integrate `com.github.nullstress:dependency-analysis` Gradle plugin
- Parse `build/reports/dependency-analysis/report.txt`

---

## Next Steps

After implementation:

1. **Collect baseline metrics for 7 days** before starting refactoring
2. **Export "Before Refactoring" dashboard snapshot** (PDF/JSON)
3. **Communicate baseline** to team via Slack/Email
4. **Start refactoring** using [ADR-014](../adr/ADR-014-multi-module-cross-cutting-concerns.md)
5. **Re-run verification script** after each major refactoring milestone
6. **Update "After Refactoring" dashboard** to track progress
7. **Document improvements** in refactoring retrospective

---

## References

- [Verification Strategy](../metrics/verification-strategy.md)
- [ADR-014: Multi-Module Cross-Cutting Concerns](../adr/ADR-014-multi-module-cross-cutting-concerns.md)
- [Multi-Module Refactoring Analysis](./Multi-Module-Refactoring-Analysis.md)
- [Spring Boot Actuator Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Micrometer Reference](https://micrometer.io/docs)
- [Grafana Dashboard JSON Reference](https://grafana.com/docs/grafana/latest/dashboards/share-dashboard-dashboards/)
