#!/bin/bash
# Test Inventory Script for MapleExpectation Refactor
# Owner: Yellow QA Master (Phase 0)
# Description: Categorizes and counts tests for risk assessment

set -euo pipefail

cd "$(dirname "$0")/../.." || exit 1

echo "========================================"
echo "MapleExpectation Test Inventory Report"
echo "========================================"
echo ""
echo "Generated: $(date)"
echo "Project: $(pwd)"
echo ""

# 1. Test Counts by Category
echo "## 1. Test Inventory by Category"
echo "----------------------------------------"

echo ""
echo "### Chaos/Nightmare Tests"
find src/test -path "*/chaos/nightmare/*" -name "*Test.java" | wc -l
echo "Files:"
find src/test -path "*/chaos/nightmare/*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Chaos Core Tests"
find src/test -path "*/chaos/core/*" -name "*Test.java" | wc -l
echo "Files:"
find src/test -path "*/chaos/core/*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Chaos Network Tests"
find src/test -path "*/chaos/network/*" -name "*Test.java" | wc -l
echo "Files:"
find src/test -path "*/chaos/network/*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Chaos Resource Tests"
find src/test -path "*/chaos/resource/*" -name "*Test.java" | wc -l
echo "Files:"
find src/test -path "*/chaos/resource/*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Chaos Connection Tests"
find src/test -path "*/chaos/connection/*" -name "*Test.java" | wc -l
echo "Files:"
find src/test -path "*/chaos/connection/*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

# 2. Integration Tests
echo ""
echo "## 2. Integration Tests (Testcontainers)"
echo "----------------------------------------"
grep -r "Testcontainers\|@Container\|AbstractContainerBaseTest" src/test --include="*.java" -l | wc -l

# 3. Concurrency Tests
echo ""
echo "## 3. Concurrency Tests"
echo "----------------------------------------"
find src/test -name "*ConcurrencyTest.java" | wc -l
echo "Files:"
find src/test -name "*ConcurrencyTest.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

# 4. Module-specific tests
echo ""
echo "## 4. Module-Specific Tests"
echo "----------------------------------------"

echo ""
echo "### LogicExecutor Tests:"
find src/test -path "*executor*" -name "*Test.java" | wc -l
find src/test -path "*executor*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Resilience4j Tests:"
find src/test -path "*resilience*" -name "*Test.java" | wc -l
find src/test -path "*resilience*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### TieredCache Tests:"
find src/test -path "*cache*" -name "*Test.java" | wc -l
find src/test -path "*cache*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Graceful Shutdown Tests:"
find src/test -path "*shutdown*" -name "*Test.java" | wc -l
find src/test -path "*shutdown*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Outbox Tests:"
find src/test -path "*outbox*" -name "*Test.java" | wc -l
find src/test -path "*outbox*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### AOP Tests:"
find src/test -path "*aop*" -name "*Test.java" | wc -l
find src/test -path "*aop*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Controller Tests:"
find src/test -path "*controller*" -name "*Test.java" | wc -l
find src/test -path "*controller*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Queue Tests:"
find src/test -path "*queue*" -name "*Test.java" | wc -l
find src/test -path "*queue*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Lock Tests:"
find src/test -path "*lock*" -name "*Test.java" | wc -l
find src/test -path "*lock*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

echo ""
echo "### Rate Limit Tests:"
find src/test -path "*ratelimit*" -name "*Test.java" | wc -l
find src/test -path "*ratelimit*" -name "*Test.java" | while read -r file; do
    echo "  - $(basename "$file")"
done

# 5. Flaky Test Patterns
echo ""
echo "## 5. Flaky Test Pattern Detection"
echo "----------------------------------------"

echo ""
echo "### Thread.sleep() Usage (SHOULD BE ZERO):"
grep -r "Thread\.sleep" src/test --include="*.java" | wc -l
grep -r "Thread\.sleep" src/test --include="*.java" | head -5 || echo "  None found - GOOD"

echo ""
echo "### Awaitility Usage (PREFERRED):"
grep -r "Awaitility\.await\|await()" src/test --include="*.java" | wc -l

echo ""
echo "### CountDownLatch Usage (PREFERRED):"
grep -r "CountDownLatch" src/test --include="*.java" | wc -l

echo ""
echo "### awaitTermination() Usage (REQUIRED):"
grep -r "awaitTermination" src/test --include="*.java" | wc -l

# 6. Test Coverage Summary
echo ""
echo "## 6. Coverage Summary"
echo "----------------------------------------"
echo ""
echo "Total Test Files: $(find src/test -name "*Test.java" | wc -l)"
echo "Total @Test Methods: $(grep -r "@Test" src/test --include="*.java" | wc -l)"
echo "Main Source Files: $(find src/main -name "*.java" | wc -l)"
echo "Test:Source Ratio: $(echo "scale=2; $(grep -r "@Test" src/test --include="*.java" | wc -l) / $(find src/main -name "*.java" | wc -l)" | bc)"

echo ""
echo "========================================"
echo "Test Inventory Complete"
echo "========================================"
