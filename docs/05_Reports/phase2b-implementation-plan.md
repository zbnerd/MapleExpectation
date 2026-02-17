# Phase 2-B: Calculator Extraction Implementation Plan

## Overview

Extract pure business logic from `module-app` to `module-core`, establishing a clean separation between domain logic and infrastructure concerns.

**Context:**
- Phase 1 complete: `module-common` extraction (59 files)
- Phase 2-A complete: Port interfaces defined (5 ports, 7 domain models)
- Clean build: 3m 47s

**Goal:** Move calculators to `module-core` while maintaining full functionality through temporary adapters in `module-app`.

---

## Pre-Flight Checklist

- [ ] Clean build passing (3m 47s)
- [ ] Port interfaces defined in `module-core` (Phase 2-A)
  - [ ] `CubeRatePort`
  - [ ] `EquipmentDataPort`
  - [ ] `AlertPort`
  - [ ] `PotentialStatPort`
  - [ ] `ItemPricePort`
- [ ] Domain models defined in `module-core`
  - [ ] `CubeRate`
  - [ ] `CubeType`
  - [ ] `AlertMessage`, `AlertPriority`
  - [ ] `PotentialStat`
  - [ ] `ItemPrice`
  - [ ] `CharacterId`
- [ ] Git tag created: `pre-phase-2b`
- [ ] Rollback strategy reviewed
- [ ] Test coverage baseline documented

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        module-app (Spring)                          │
│  ┌─────────────────┐      ┌──────────────────────────────────────┐ │
│  │   Controllers   │──────│   Application Services               │ │
│  │  (V4, V5, etc.) │      │  - orchestrate use cases             │ │
│  └─────────────────┘      │  - use Ports                          │ │
│                           └──────────────────────────────────────┘ │
│                                    │                                │
│                           ┌────────▼──────────────────────────────┐ │
│                           │   TemporaryAdapterConfig              │ │
│                           │   - Implements Ports                  │ │
│                           │   - Wraps Repositories                │ │
│                           │   - Returns Domain Models             │ │
│                           └────────┬──────────────────────────────┘ │
│                                    │                                │
│                           ┌────────▼──────────────────────────────┐ │
│                           │   Repositories (CSV, DB, etc.)        │ │
│                           │   - Infrastructure concerns           │ │
│                           └──────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                            ┌───────┴────────┐
                            │ Port Interface │ (uses)
                            └───────┬────────┘
                                    │
┌─────────────────────────────────────────────────────────────────────┐
│                        module-core (Pure Java)                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                     Domain Layer                             │  │
│  │  - StatType, StatParser                                       │  │
│  │  - CubeRate, CubeType, PotentialStat                          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                   Calculator Layer                           │  │
│  │  - PotentialCalculator (pure logic)                          │  │
│  │  - CubeRateCalculator (uses CubeRatePort)                    │  │
│  │  - ProbabilityConvolver (DP algorithms)                      │  │
│  │  - TailProbabilityCalculator                                 │  │
│  │  - FlameScoreCalculator                                      │  │
│  │  - StarforceCalculator                                       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                      Port Layer                              │  │
│  │  - CubeRatePort, EquipmentDataPort, etc.                     │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Step-by-Step Implementation

### Step 1: Create Domain Models in module-core (1 hour)

**Objective:** Move `StatParser` and `StatType` to `module-core` as pure domain objects.

**1.1 Create package structure:**

```bash
mkdir -p module-core/src/main/java/maple/expectation/core/domain/stat
```

**1.2 Move `StatType.java`:**

Source: `module-app/src/main/java/maple/expectation/util/StatType.java`
Target: `module-core/src/main/java/maple/expectation/core/domain/stat/StatType.java`

**Transformation:**

```java
// Before (module-app)
package maple.expectation.util;

@Getter
public enum StatType {
    // ... enum values
}

// After (module-core)
package maple.expectation.core.domain.stat;

@Getter
public enum StatType {
    // ... enum values (NO CHANGES - pure enum)
}
```

**1.3 Move `StatParser.java`:**

Source: `module-app/src/main/java/maple/expectation/util/StatParser.java`
Target: `module-core/src/main/java/maple/expectation/core/domain/stat/StatParser.java`

**Transformation:**

```java
// Before (module-app) - Has LogicExecutor + Spring annotations
@Slf4j
@Component
@RequiredArgsConstructor
public class StatParser {
    private final LogicExecutor executor;

    public int parseNum(String value) {
        return executor.executeOrDefault(
            () -> { /* parsing logic */ },
            0,
            TaskContext.of("Parser", "StatParse", value)
        );
    }
}

// After (module-core) - Pure Java, no Spring, no LogicExecutor
public class StatParser {
    public int parseNum(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            String cleanStr = value.replaceAll("[^0-9\\-]", "");
            return cleanStr.isEmpty() ? 0 : Integer.parseInt(cleanStr);
        } catch (NumberFormatException e) {
            return 0; // Fail-fast: caller decides what to do
        }
    }

    public boolean isPercent(String value) {
        return value != null && value.contains("%");
    }
}
```

**Verification:**

```bash
# Compile module-core
./gradlew :module-core:compileJava

# Should succeed with 0 errors
```

**Risk Mitigation:**
- **Risk:** `StatParser` callers expect `LogicExecutor` exception handling
- **Mitigation:** Move exception handling to ApplicationService layer
- **Rollback:** `git checkout module-app/src/main/java/maple/expectation/util/`

---

### Step 2: Extract PotentialCalculator (2 hours)

**Objective:** Move `PotentialCalculator` to `module-core` as a pure calculator.

**2.1 Create package structure:**

```bash
mkdir -p module-core/src/main/java/maple/expectation/core/calculator
```

**2.2 Create `PotentialCalculator` in module-core:**

Source: `module-app/src/main/java/maple/expectation/service/v2/calculator/PotentialCalculator.java`
Target: `module-core/src/main/java/maple/expectation/core/calculator/PotentialCalculator.java`

**Transformation:**

```java
// Before (module-app)
@Slf4j
@Component
@RequiredArgsConstructor
public class PotentialCalculator {
    private final StatParser statParser;
    private final LogicExecutor executor;

    public Map<StatType, Integer> calculateMainPotential(ItemEquipment item) {
        TaskContext context = TaskContext.of("Calculator", "MainPotential", item.getItemName());
        return executor.execute(
            () -> this.sumOptions(Stream.of(item.getPotentialOption1(), ...)),
            context);
    }
}

// After (module-core) - No Spring, no LogicExecutor
package maple.expectation.core.calculator;

import maple.expectation.core.domain.stat.StatType;
import maple.expectation.core.domain.stat.StatParser;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class PotentialCalculator {
    private final StatParser statParser;

    // Constructor injection (not Spring)
    public PotentialCalculator(StatParser statParser) {
        this.statParser = statParser;
    }

    public Map<StatType, Integer> calculateMainPotential(PotentialOptions options) {
        return sumOptions(Stream.of(
            options.option1(),
            options.option2(),
            options.option3()
        ));
    }

    public Map<StatType, Integer> calculateAdditionalPotential(PotentialOptions options) {
        return sumOptions(Stream.of(
            options.additionalOption1(),
            options.additionalOption2(),
            options.additionalOption3()
        ));
    }

    // ... rest of methods (same logic, no executor wrapper)
}
```

**2.3 Create domain model for potential options:**

```java
// module-core/src/main/java/maple/expectation/core/domain/model/PotentialOptions.java
package maple.expectation.core.domain.model;

import java.util.List;

public record PotentialOptions(
    String option1,
    String option2,
    String option3,
    String additionalOption1,
    String additionalOption2,
    String additionalOption3
) {
    public static PotentialOptions from(List<String> main, List<String> additional) {
        return new PotentialOptions(
            main.get(0), main.get(1), main.get(2),
            additional.get(0), additional.get(1), additional.get(2)
        );
    }
}
```

**2.4 Create `PotentialApplicationService` in module-app:**

```java
// module-app/src/main/java/maple/expectation/application/service/PotentialApplicationService.java
package maple.expectation.application.service;

import maple.expectation.core.calculator.PotentialCalculator;
import maple.expectation.core.domain.stat.StatParser;
import maple.expectation.core.domain.stat.StatType;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PotentialApplicationService {
    private final PotentialCalculator calculator;
    private final LogicExecutor executor;

    public Map<StatType, Integer> calculateMainPotential(ItemEquipment item) {
        return executor.execute(
            () -> {
                PotentialOptions options = PotentialOptions.from(
                    List.of(item.getPotentialOption1(), item.getPotentialOption2(), item.getPotentialOption3()),
                    List.of(item.getAdditionalPotentialOption1(), item.getAdditionalPotentialOption2(), item.getAdditionalPotentialOption3())
                );
                return calculator.calculateMainPotential(options);
            },
            TaskContext.of("PotentialApplication", "CalculateMain", item.getItemName())
        );
    }
}
```

**2.5 Update bean configuration:**

```java
// module-app/src/main/java/maple/expectation/config/CalculatorConfig.java
package maple.expectation.config;

import maple.expectation.core.calculator.PotentialCalculator;
import maple.expectation.core.domain.stat.StatParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CalculatorConfig {
    @Bean
    public StatParser statParser() {
        return new StatParser();
    }

    @Bean
    public PotentialCalculator potentialCalculator(StatParser statParser) {
        return new PotentialCalculator(statParser);
    }
}
```

**Verification:**

```bash
# Test module-core
./gradlew :module-core:test

# Test module-app
./gradlew :module-app:test --tests "*Potential*"
```

**Risk Mitigation:**
- **Risk:** Constructor injection changes break existing code
- **Mitigation:** Create `@Configuration` class to bridge Spring to pure Java
- **Rollback:** `git checkout module-app/src/main/java/maple/expectation/service/v2/calculator/`

---

### Step 3: Extract CubeRateCalculator (2 hours)

**Objective:** Move `CubeRateCalculator` to `module-core`, using `CubeRatePort` for data access.

**3.1 Create `CubeRateCalculator` in module-core:**

Source: `module-app/src/main/java/maple/expectation/service/v2/calculator/CubeRateCalculator.java`
Target: `module-core/src/main/java/maple/expectation/core/calculator/CubeRateCalculator.java`

**Transformation:**

```java
// Before (module-app) - Uses CubeProbabilityRepository directly
@Component
@RequiredArgsConstructor
public class CubeRateCalculator {
    private final CubeProbabilityRepository probabilityRepository;

    public double getOptionRate(CubeType type, int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) {
            return 1.0;
        }
        StatType statType = StatType.findTypeWithUnit(optionName);
        if (statType == StatType.UNKNOWN) {
            return 1.0;
        }
        return probabilityRepository.findProbabilities(type, level, part, grade, slot).stream()
            .filter(p -> p.getOptionName().equals(optionName))
            .findFirst()
            .map(CubeProbability::getRate)
            .orElse(0.0);
    }
}

// After (module-core) - Uses CubeRatePort
package maple.expectation.core.calculator;

import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;
import maple.expectation.core.domain.stat.StatType;
import maple.expectation.core.port.out.CubeRatePort;
import java.util.List;

public class CubeRateCalculator {
    private final CubeRatePort port;

    public CubeRateCalculator(CubeRatePort port) {
        this.port = port;
    }

    public double getOptionRate(CubeType type, int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) {
            return 1.0;
        }

        StatType statType = StatType.findTypeWithUnit(optionName);
        if (statType == StatType.UNKNOWN) {
            return 1.0;
        }

        List<CubeRate> rates = port.findByCubeType(type);
        return rates.stream()
            .filter(r -> r.optionName().equals(optionName))
            .filter(r -> r.level() == level)
            .filter(r -> r.part().equals(part))
            .filter(r -> r.grade().equals(grade))
            .filter(r -> r.slot() == slot)
            .findFirst()
            .map(CubeRate::rate)
            .orElse(0.0);
    }
}
```

**3.2 Create `TemporaryAdapterConfig.CubeRatePort`:**

```java
// module-app/src/main/java/maple/expectation/config/TemporaryAdapterConfig.java
package maple.expectation.config;

import maple.expectation.core.port.out.CubeRatePort;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;
import maple.expectation.domain.repository.CubeProbabilityRepository;
import maple.expectation.domain.v2.CubeProbability;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class TemporaryAdapterConfig {
    private final CubeProbabilityRepository cubeProbabilityRepository;

    @Bean
    public CubeRatePort cubeRatePort() {
        return new CubeRatePort() {
            @Override
            public List<CubeRate> findByCubeType(CubeType type) {
                return cubeProbabilityRepository.findByType(type).stream()
                    .map(this::toDomainModel)
                    .toList();
            }

            @Override
            public List<CubeRate> findAll() {
                return cubeProbabilityRepository.findAll().stream()
                    .map(this::toDomainModel)
                    .toList();
            }

            private CubeRate toDomainModel(CubeProbability entity) {
                return new CubeRate(
                    CubeType.from(entity.getType()),
                    entity.getOptionName(),
                    entity.getRate(),
                    entity.getSlot(),
                    entity.getGrade(),
                    entity.getLevel(),
                    entity.getPart()
                );
            }
        };
    }
}
```

**3.3 Create `CubeRate` domain model (if not exists):**

```java
// module-core/src/main/java/maple/expectation/core/domain/model/CubeType.java (update if needed)
package maple.expectation.core.domain.model;

public enum CubeType {
    BLACK, RED, ADDITIONAL;

    public static CubeType from(maple.expectation.domain.v2.CubeType v2Type) {
        return switch (v2Type) {
            case BLACK_CUBE -> BLACK;
            case RED_CUBE -> RED;
            case ADDITIONAL_CUBE -> ADDITIONAL;
        };
    }
}
```

**Verification:**

```bash
# Test the port implementation
./gradlew :module-app:test --tests "*CubeRate*"

# Test module-core
./gradlew :module-core:test
```

**Risk Mitigation:**
- **Risk:** Port implementation doesn't match repository behavior
- **Mitigation:** Write adapter tests comparing old vs new results
- **Rollback:** Remove bean, use repository directly

---

### Step 4: Extract ProbabilityConvolver (2 hours)

**Objective:** Move pure DP algorithms to `module-core`.

**4.1 Create `ProbabilityConvolver` in module-core:**

Source: `module-app/src/main/java/maple/expectation/service/v2/cube/component/ProbabilityConvolver.java`
Target: `module-core/src/main/java/maple/expectation/core/calculator/ProbabilityConvolver.java`

**Transformation:**

```java
// Before (module-app)
@Slf4j
@Component
@RequiredArgsConstructor
public class ProbabilityConvolver {
    private final LogicExecutor executor;

    public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
        return executor.execute(
            () -> doConvolveWithClamp(slotPmfs, target, enableTailClamp),
            TaskContext.of("Convolver", "ConvolveAll", "target=" + target)
        );
    }
}

// After (module-core)
package maple.expectation.core.calculator;

import maple.expectation.core.domain.model.calculator.DensePmf;
import maple.expectation.core.domain.model.calculator.SparsePmf;
import maple.expectation.core.domain.exception.ProbabilityInvariantException;
import java.util.List;

public class ProbabilityConvolver {
    private static final double MASS_TOLERANCE = 1e-12;
    private static final double NEGATIVE_TOLERANCE = -1e-15;

    public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
        // Direct execution - no executor wrapper
        return doConvolveWithClamp(slotPmfs, target, enableTailClamp);
    }

    // ... rest of methods (same logic)
}
```

**4.2 Move domain models if needed:**

```bash
# Check if DensePmf/SparsePmf already in module-core
ls module-core/src/main/java/maple/expectation/core/domain/model/calculator/

# If not, move from module-app
```

**Verification:**

```bash
# Test DP calculations
./gradlew :module-core:test --tests "*ProbabilityConvolver*"

# Run integration tests
./gradlew :module-app:test --tests "*DpCalculator*"
```

**Risk Mitigation:**
- **Risk:** Numeric precision changes without executor logging
- **Mitigation:** Keep exact same algorithm, compare results
- **Rollback:** Direct file restore

---

### Step 5: Extract TailProbabilityCalculator (1 hour)

**Objective:** Move pure probability calculations to `module-core`.

**5.1 Create `TailProbabilityCalculator` in module-core:**

Source: `module-app/src/main/java/maple/expectation/service/v2/cube/component/TailProbabilityCalculator.java`
Target: `module-core/src/main/java/maple/expectation/core/calculator/TailProbabilityCalculator.java`

**Transformation:**

```java
// Before (module-app)
@Component
public class TailProbabilityCalculator {
    // ... methods
}

// After (module-core)
package maple.expectation.core.calculator;

import maple.expectation.core.domain.model.calculator.DensePmf;

public class TailProbabilityCalculator {
    public double calculateTailProbability(DensePmf pmf, int target, boolean tailClampApplied) {
        if (tailClampApplied) {
            return pmf.massAt(target);
        }
        return kahanSumFrom(pmf, target);
    }

    // ... rest of methods (same logic)
}
```

**Verification:**

```bash
./gradlew :module-core:test --tests "*TailProbability*"
```

---

### Step 6: Extract FlameCalculator Components (2 hours)

**Objective:** Move flame calculation logic to `module-core`.

**6.1 Components to extract:**

- `FlameScoreCalculator` → `module-core/calculator/`
- `FlameDpCalculator` → `module-core/calculator/`
- `FlameScoreResolver` → `module-core/calculator/`
- `FlameOptionType` → `module-core/domain/model/flame/`
- `FlameType` → `module-core/domain/model/flame/`
- `FlameEquipCategory` → `module-core/domain/model/flame/`

**6.2 Create flame domain package:**

```bash
mkdir -p module-core/src/main/java/maple/expectation/core/domain/model/flame
```

**6.3 Transform `FlameScoreCalculator`:**

```java
// Before (module-app)
@Component
public class FlameScoreCalculator {
    // ... pure calculation logic
}

// After (module-core)
package maple.expectation.core.calculator;

import maple.expectation.core.domain.model.flame.FlameOptionType;
import maple.expectation.core.domain.model.flame.FlameEquipCategory;
import java.util.List;
import java.util.Map;

public class FlameScoreCalculator {
    public Integer calculateScore(
        FlameOptionType option,
        int level,
        int stage,
        JobWeights weights,
        boolean isWeapon,
        int baseAtt,
        int baseMag
    ) {
        // Same logic, no @Component
    }
}
```

**6.4 Move config classes:**

`FlameStageProbability`, `FlameStatTable`, `JobStatMapping` → `module-core/domain/config/flame/`

**Verification:**

```bash
./gradlew :module-core:test
./gradlew :module-app:test --tests "*Flame*"
```

---

### Step 7: Extract StarforceCalculator (2 hours)

**Objective:** Move starforce calculation to `module-core`.

**7.1 Create `StarforceCalculator` in module-core:**

Source: `module-app/src/main/java/maple/expectation/service/v2/starforce/StarforceLookupTableImpl.java`
Target: `module-core/src/main/java/maple/expectation/core/calculator/StarforceCalculator.java`

**Transformation:**

```java
// Before (module-app)
@Slf4j
@Component
@RequiredArgsConstructor
public class StarforceLookupTableImpl implements StarforceLookupTable {
    private final LogicExecutor executor;
    private final ConcurrentHashMap<String, BigDecimal> expectedCostCache;

    @Override
    public void initialize() {
        executor.executeVoid(this::precomputeTables, TaskContext.of("Starforce", "Initialize"));
    }
}

// After (module-core)
package maple.expectation.core.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;

public class StarforceCalculator {
    private final ConcurrentHashMap<String, BigDecimal> expectedCostCache;

    public StarforceCalculator() {
        this.expectedCostCache = new ConcurrentHashMap<>();
    }

    public void initialize() {
        precomputeTables(); // Direct call, no executor
    }

    // ... rest of methods
}
```

**7.2 Create `StarforceApplicationService`:**

```java
// module-app/src/main/java/maple/expectation/application/service/StarforceApplicationService.java
package maple.expectation.application.service;

import maple.expectation.core.calculator.StarforceCalculator;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

@Service
@RequiredArgsConstructor
public class StarforceApplicationService {
    private final StarforceCalculator calculator;
    private final LogicExecutor executor;

    @PostConstruct
    public void initialize() {
        executor.executeVoid(
            calculator::initialize,
            TaskContext.of("Starforce", "Init")
        );
    }

    public BigDecimal getExpectedCost(int currentStar, int targetStar, int itemLevel) {
        return executor.execute(
            () -> calculator.getExpectedCost(currentStar, targetStar, itemLevel),
            TaskContext.of("Starforce", "GetExpectedCost")
        );
    }
}
```

**Verification:**

```bash
./gradlew :module-core:test
./gradlew :module-app:test --tests "*Starforce*"
```

---

### Step 8: Update All References (3 hours)

**Objective:** Find and update all references to moved classes.

**8.1 Find all references:**

```bash
# Find references to moved calculators
grep -r "PotentialCalculator" module-app/src --include="*.java"
grep -r "CubeRateCalculator" module-app/src --include="*.java"
grep -r "ProbabilityConvolver" module-app/src --include="*.java"
grep -r "TailProbabilityCalculator" module-app/src --include="*.java"
grep -r "FlameScoreCalculator" module-app/src --include="*.java"
grep -r "StarforceLookupTable" module-app/src --include="*.java"

# Find references to StatParser/StatType
grep -r "import.*util\.StatParser" module-app/src --include="*.java"
grep -r "import.*util\.StatType" module-app/src --include="*.java"
```

**8.2 Update imports systematically:**

| Old Import | New Import |
|------------|------------|
| `maple.expectation.util.StatParser` | `maple.expectation.core.domain.stat.StatParser` |
| `maple.expectation.util.StatType` | `maple.expectation.core.domain.stat.StatType` |
| `maple.expectation.service.v2.calculator.PotentialCalculator` | `maple.expectation.core.calculator.PotentialCalculator` |
| `maple.expectation.service.v2.calculator.CubeRateCalculator` | `maple.expectation.core.calculator.CubeRateCalculator` |
| `maple.expectation.service.v2.cube.component.ProbabilityConvolver` | `maple.expectation.core.calculator.ProbabilityConvolver` |
| `maple.expectation.service.v2.cube.component.TailProbabilityCalculator` | `maple.expectation.core.calculator.TailProbabilityCalculator` |

**8.3 Update constructor injections:**

Files to update:
- `CubeDpCalculator` → Inject from new package
- `CubeServiceImpl` → Inject from new package
- Any controller/facade using calculators directly

**8.4 Update test imports:**

```bash
# Find test files
grep -r "import.*service\.v2\.calculator" module-app/src/test --include="*.java"

# Update to use new packages
sed -i 's/maple\.expectation\.service\.v2\.calculator/maple.expectation.core.calculator/g' $(find module-app/src/test -name "*.java")
sed -i 's/maple\.expectation\.service\.v2\.cube\.component/maple.expectation.core.calculator/g' $(find module-app/src/test -name "*.java")
sed -i 's/maple\.expectation\.util\.StatParser/maple.expectation.core.domain.stat.StatParser/g' $(find module-app/src/test -name "*.java")
sed -i 's/maple\.expectation\.util\.StatType/maple.expectation.core.domain.stat.StatType/g' $(find module-app/src/test -name "*.java")
```

**Verification:**

```bash
# Compile check
./gradlew compileJava

# Full build
./gradlew clean build -x test
```

---

### Step 9: Integration Testing (2 hours)

**Objective:** Verify all functionality works after extraction.

**9.1 Port implementation tests:**

```java
// module-app/src/test/java/maple/expectation/config/TemporaryAdapterConfigTest.java
@Import(TemporaryAdapterConfig.class)
class TemporaryAdapterConfigTest {
    @Autowired
    private CubeRatePort cubeRatePort;

    @Test
    void cubeRatePort_shouldReturnCubeRates() {
        List<CubeRate> rates = cubeRatePort.findByCubeType(CubeType.BLACK);
        assertThat(rates).isNotEmpty();
    }
}
```

**9.2 ApplicationService integration tests:**

```java
// module-app/src/test/java/maple/expectation/application/service/PotentialApplicationServiceTest.java
@SpringBootTest
class PotentialApplicationServiceTest {
    @Autowired
    private PotentialApplicationService service;

    @Test
    void calculateMainPotential_shouldReturnValidStats() {
        ItemEquipment item = createTestItem();
        Map<StatType, Integer> result = service.calculateMainPotential(item);
        assertThat(result).isNotEmpty();
    }
}
```

**9.3 API endpoint tests:**

```bash
# Run full test suite
./gradlew test

# Run specific controller tests
./gradlew test --tests "*Controller*"

# Verify API responses unchanged
curl -X GET "http://localhost:8080/api/v4/characters/testIGN/expectation" -H "Authorization: Bearer $TOKEN"
```

**9.4 Performance verification:**

```bash
# Build time should be <4 minutes
time ./gradlew clean build

# Memory check during tests
./gradlew test --info | grep -i "heap"
```

---

## Risk Mitigation

| Risk | Probability | Impact | Mitigation Strategy |
|------|-------------|--------|---------------------|
| Circular dependency (core → app) | Medium | High | Use Port interfaces, temporary adapters in app only |
| LogicExecutor behavior changes | Low | Medium | Wrap core calls in ApplicationService with executor |
| Test failures due to imports | High | Low | Systematic import update script |
| Build time increase | Low | Low | Monitor, optimize module-core if needed |
| Runtime classpath issues | Medium | Medium | Verify with integration tests before commit |

### Rollback Strategy

**If Step fails:**

1. **Steps 1-5 (Domain Models):**
   ```bash
   git checkout module-app/src/main/java/maple/expectation/util/
   rm -rf module-core/src/main/java/maple/expectation/core/domain/stat/
   ```

2. **Steps 6-7 (Calculators):**
   ```bash
   git checkout module-app/src/main/java/maple/expectation/service/v2/calculator/
   git checkout module-app/src/main/java/maple/expectation/service/v2/cube/
   rm -rf module-core/src/main/java/maple/expectation/core/calculator/
   ```

3. **Steps 8-9 (References/Tests):**
   ```bash
   git checkout module-app/src/
   git checkout module-app/src/test/
   ```

**Complete rollback:**

```bash
# Create rollback tag before starting
git tag pre-phase-2b

# Rollback if needed
git checkout pre-phase-2b
git branch -D phase-2b-extraction
```

---

## Success Criteria

### Must Have (P0)
- [ ] All calculators moved to `module-core` (Spring-free)
- [ ] `StatParser` and `StatType` in `module-core/domain/stat/`
- [ ] ApplicationServices in `module-app/application/service/`
- [ ] `TemporaryAdapterConfig` implements all Ports
- [ ] All tests passing (`./gradlew test`)
- [ ] Clean build (`./gradlew clean build`)
- [ ] Zero Spring dependencies in `module-core`

### Should Have (P1)
- [ ] Build time <4 minutes
- [ ] No warnings in compilation
- [ ] API responses unchanged (integration verified)
- [ ] Test coverage maintained (no reduction)

### Nice to Have (P2)
- [ ] Javadoc for new core classes
- [ ] Package-info.java for new packages
- [ ] Migration guide document

---

## Verification Commands

### After Each Step

```bash
# 1. Compile module-core
./gradlew :module-core:compileJava

# 2. Compile module-app
./gradlew :module-app:compileJava

# 3. Run affected tests
./gradlew :module-core:test
./gradlew :module-app:test --tests "*[StepName]*"
```

### After Completion

```bash
# 1. Full clean build
./gradlew clean build

# 2. All tests
./gradlew test

# 3. Check for Spring in core
grep -r "org.springframework" module-core/src/main/java
# Should return empty or minimal (only in test)

# 4. Check for LogicExecutor in core
grep -r "LogicExecutor" module-core/src/main/java
# Should return empty

# 5. Dependency check
./gradlew :module-core:dependencies | grep spring
# Should show no spring dependencies
```

---

## Code Examples for Key Transformations

### Pattern 1: Removing @Component

```java
// Before
@Slf4j
@Component
@RequiredArgsConstructor
public class MyCalculator {
    private final Dependency dep;
}

// After
public class MyCalculator {
    private final Dependency dep;

    public MyCalculator(Dependency dep) {
        this.dep = dep;
    }
}
```

### Pattern 2: Removing LogicExecutor

```java
// Before
public Result calculate(Input in) {
    return executor.execute(
        () -> doCalculate(in),
        TaskContext.of("MyCalc", "Calculate")
    );
}

// After (core - no executor)
public Result calculate(Input in) {
    return doCalculate(in);
}

// After (app - wraps core)
public Result calculate(Input in) {
    return executor.execute(
        () -> coreCalculator.calculate(in),
        TaskContext.of("MyApp", "Calculate")
    );
}
```

### Pattern 3: Port Implementation

```java
// Core - Port interface
public interface MyPort {
    List<MyModel> findAll();
}

// App - Temporary adapter
@Configuration
public class TemporaryAdapterConfig {
    @Bean
    public MyPort myPort(MyRepository repository) {
        return () -> repository.findAll()
            .stream()
            .map(this::toDomain)
            .toList();
    }
}
```

---

## Post-Phase 2-B Checklist

- [ ] All calculators in `module-core` verified Spring-free
- [ ] All ApplicationServices created and tested
- [ ] All imports updated across codebase
- [ ] All tests passing (unit + integration)
- [ ] API contract unchanged
- [ ] Build time within acceptable range
- [ ] Documentation updated
- [ ] Git tag created: `post-phase-2b`
- [ ] Ready for Phase 3 (Infrastructure Adapters)

---

## Next Phase Preview: Phase 3 - Infrastructure Adapters

After Phase 2-B completion:

1. Create `module-infra` with real port implementations
2. Move `CubeProbabilityRepository` → `module-infra`
3. Remove `TemporaryAdapterConfig`
4. Update module dependencies: `app → infra → core`

---

## Appendix: File Mapping

### Files to Move

| Source (module-app) | Target (module-core) |
|---------------------|----------------------|
| `util/StatParser.java` | `core/domain/stat/StatParser.java` |
| `util/StatType.java` | `core/domain/stat/StatType.java` |
| `service/v2/calculator/PotentialCalculator.java` | `core/calculator/PotentialCalculator.java` |
| `service/v2/calculator/CubeRateCalculator.java` | `core/calculator/CubeRateCalculator.java` |
| `service/v2/cube/component/ProbabilityConvolver.java` | `core/calculator/ProbabilityConvolver.java` |
| `service/v2/cube/component/TailProbabilityCalculator.java` | `core/calculator/TailProbabilityCalculator.java` |
| `service/v2/flame/component/FlameScoreCalculator.java` | `core/calculator/FlameScoreCalculator.java` |
| `service/v2/flame/component/FlameDpCalculator.java` | `core/calculator/FlameDpCalculator.java` |
| `service/v2/flame/FlameOptionType.java` | `core/domain/model/flame/FlameOptionType.java` |
| `service/v2/flame/FlameType.java` | `core/domain/model/flame/FlameType.java` |
| `service/v2/flame/FlameEquipCategory.java` | `core/domain/model/flame/FlameEquipCategory.java` |
| `service/v2/starforce/StarforceLookupTableImpl.java` | `core/calculator/StarforceCalculator.java` |
| `service/v2/starforce/config/NoljangProbabilityTable.java` | `core/domain/config/starforce/NoljangProbabilityTable.java` |
| `service/v2/flame/config/FlameStageProbability.java` | `core/domain/config/flame/FlameStageProbability.java` |
| `service/v2/flame/config/FlameStatTable.java` | `core/domain/config/flame/FlameStatTable.java` |
| `service/v2/flame/config/JobStatMapping.java` | `core/domain/config/flame/JobStatMapping.java` |

### Files to Create

| Path | Purpose |
|------|---------|
| `module-app/config/CalculatorConfig.java` | Bean definitions for core calculators |
| `module-app/config/TemporaryAdapterConfig.java` | Port implementations using repositories |
| `module-app/application/service/PotentialApplicationService.java` | Orchestrate potential calculations |
| `module-app/application/service/CubeApplicationService.java` | Orchestrate cube calculations |
| `module-app/application/service/StarforceApplicationService.java` | Orchestrate starforce calculations |
| `module-app/application/service/FlameApplicationService.java` | Orchestrate flame calculations |

---

**Document Version:** 1.0
**Last Updated:** 2026-02-16
**Owner:** Prometheus (Planning Agent)
**Status:** Ready for Execution
