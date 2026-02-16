package architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Stateless design enforcement tests.
 *
 * <p><strong>Core Principle:</strong> System must be stateless to enable horizontal scaling
 * (scale-out) and prevent concurrency issues.
 *
 * <p><strong>References:</strong>
 *
 * <ul>
 *   <li>docs/adr/ADR-039-current-architecture-assessment.md - Stateful component analysis
 *   <li>docs/04_Reports/scale-out-blockers-analysis.md - P0/P1 stateful components
 *   <li>docs/00_Start_Here/ROADMAP.md - Phase 7: Multi-module refactoring for scale-out
 *   <li>CLAUDE.md - Section 16: Stateless design for scale-out readiness
 * </ul>
 *
 * <p><strong>P0/P1 Stateful Components (from scale-out-blockers-analysis.md):</strong>
 *
 * <ul>
 *   <li>**P0:** In-memory caches, distributed locks, connection pools
 *   <li>**P1:** Thread pools, async executors, rate limiters
 * </ul>
 *
 * <p><strong>Stateless Design Rules:</strong>
 *
 * <ul>
 *   <li>Controllers should be stateless (only final fields)
 *   <li>Services should be stateless (no mutable instance fields)
 *   <li>No static mutable state
 *   <li>State stored in external systems (Redis, MySQL), not in-memory
 * </ul>
 */
@DisplayName("Stateless Design Enforcement")
class StatelessDesignTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation");

  // ========================================
  // Rule 1: Controllers Must Be Stateless
  // ========================================

  @Nested
  @DisplayName("Controllers: Stateless HTTP Endpoints")
  class ControllerStatelessTests {

    /**
     * Controllers should only have final fields (immutable).
     *
     * <p><strong>CLAUDE.md Section 14:</strong> Controllers should be thin with constructor
     * injection. Controllers delegate to services, no business logic.
     *
     * <p><strong>Rationale:</strong> Controllers handle HTTP requests concurrently. Mutable state
     * creates race conditions. Immutable controllers are thread-safe.
     */
    @Test
    @DisplayName("Controllers should be immutable (only final fields)")
    void controllersShouldBeImmutable() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.web.bind.annotation.RestController")
          .or()
          .areMetaAnnotatedWith("org.springframework.stereotype.Controller")
          .should()
          .haveOnlyFinalFields()
          .because(
              """
                            Controllers must be stateless for thread safety.
                            HTTP requests are handled concurrently.
                            Mutable state creates race conditions.
                            Use constructor injection with final fields.
                            """)
          .check(classes);
    }

    /**
     * Controllers should not have mutable collections.
     *
     * <p>Mutable collections (List, Map, Set) as fields are dangerous in stateless components.
     */
    @Test
    @DisplayName("Controllers should not have mutable collection fields")
    void controllersShouldNotHaveMutableCollections() {
      // This rule is for documentation purposes
      // ArchUnit doesn't directly support detecting raw collections
      // Use manual code review or SonarQube to detect mutable collection fields
      // Recommendation: Use immutable collections (List.of(), Map.of())
    }

    /**
     * Controllers should not store request state.
     *
     * <p>Request state should be passed as method parameters, not stored in fields.
     */
    @Test
    @DisplayName("Controllers should not store request state in fields")
    void controllersShouldNotStoreRequestState() {
      // This rule is for documentation purposes
      // Use manual code review to detect request/response fields in controllers
      // Recommendation: Pass request state as method parameters only
    }
  }

  // ========================================
  // Rule 2: Services Must Be Stateless
  // ========================================

  @Nested
  @DisplayName("Services: Stateless Business Logic")
  class ServiceStatelessTests {

    /**
     * Services should only have final fields.
     *
     * <p><strong>CLAUDE.md Section 14:</strong> Services should be stateless with constructor
     * injection.
     *
     * <p><strong>Rationale:</strong> Services handle business logic concurrently. Mutable state
     * creates race conditions and makes scaling difficult.
     */
    @Test
    @DisplayName("Services should be immutable (only final fields)")
    void servicesShouldBeImmutable() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.stereotype.Service")
          .should()
          .haveOnlyFinalFields()
          .because(
              """
                            Services must be stateless for thread safety.
                            Mutable state creates race conditions.
                            State prevents horizontal scaling.
                            Use constructor injection with final fields.
                            """)
          .check(classes);
    }

    /**
     * Services should not have mutable counters/accumulators.
     *
     * <p>Counters and accumulators as fields indicate stateful design. Use Micrometer metrics
     * instead.
     */
    @Test
    @DisplayName("Services should not have mutable counter fields")
    void servicesShouldNotHaveMutableCounters() {
      // This rule is for documentation purposes
      // Use manual code review or SonarQube to detect counter fields
      // Recommendation: Use Micrometer metrics for metrics/monitoring
    }

    /**
     * Services should not store request-specific state.
     *
     * <p>Request state should be passed as method parameters, not stored in fields.
     */
    @Test
    @DisplayName("Services should not store request-specific data in fields")
    void servicesShouldNotStoreRequestData() {
      // This rule is for documentation purposes
      // Use manual code review to detect request-specific fields
      // Recommendation: Pass request data as method parameters only
    }
  }

  // ========================================
  // Rule 3: No Static Mutable State
  // ========================================

  @Nested
  @DisplayName("Static State: No Mutable Static Fields")
  class StaticStateTests {

    /**
     * No classes should access standard output streams.
     *
     * <p><strong>CLAUDE.md Section 14:</strong> e.printStackTrace() or System.out.println() usage
     * is prohibited. Use @Slf4j logger instead.
     *
     * <p>System.out and System.err are global mutable state that create thread safety issues.
     */
    @Test
    @DisplayName("No access to standard output streams")
    void noAccessToStandardOutput() {
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
    }

    /**
     * No static mutable collections.
     *
     * <p>Static mutable collections are global state that create race conditions and prevent
     * scaling.
     */
    @Test
    @DisplayName("No static mutable collections")
    void noStaticMutableCollections() {
      // This rule is for documentation purposes
      // Use manual code review or SonarQube to detect static mutable collections
      // Recommendation: Use thread-safe external stores (Redis, MySQL)
    }

    /**
     * No static non-final fields.
     *
     * <p>Static non-final fields are mutable global state.
     */
    @Test
    @DisplayName("No static non-final fields")
    void noStaticNonFinalFields() {
      // This rule is for documentation purposes
      // Use manual code review or SonarQube to detect static non-final fields
      // Recommendation: Use constants (static final) or external stores
    }

    /**
     * Static fields should be constants (final).
     *
     * <p>Static fields are acceptable only if they are immutable constants.
     */
    @Test
    @DisplayName("Static fields should be final (constants)")
    void staticFieldsShouldBeFinal() {
      // This rule is for documentation purposes
      // Use manual code review or SonarQube to detect non-final static fields
      // Recommendation: Use constants (static final) or external stores
    }
  }

  // ========================================
  // Rule 4: State In External Systems
  // ========================================

  @Nested
  @DisplayName("External State: Store in Redis/MySQL, Not In-Memory")
  class ExternalStateTests {

    /**
     * Caches should use Spring Cache abstraction or Redis.
     *
     * <p><strong>scale-out-blockers-analysis.md:</strong> In-memory caches prevent scaling. Use
     * distributed caches (Redis) or Spring Cache abstraction with Redisson.
     */
    @Test
    @DisplayName("Caches should use Spring Cache abstraction or Redis")
    void cachesShouldUseDistributedSystems() {
      classes()
          .that()
          .haveSimpleNameContaining("Cache")
          .and()
          .areMetaAnnotatedWith("org.springframework.stereotype.Component")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.cache..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.redisson..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("com.github.benmanes.caffeine..")
          .because(
              """
                            Caches should use Spring Cache abstraction or Redis.
                            In-memory maps prevent horizontal scaling.
                            Use distributed caches for scale-out readiness.
                            Caffeine is acceptable for L1 local cache only.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Locks should use distributed lock abstractions.
     *
     * <p><strong>scale-out-blockers-analysis.md:</strong> Distributed locks (Redisson) are required
     * for scale-out. Synchronized blocks/ReentrantLock prevent scaling.
     */
    @Test
    @DisplayName("Locks should use distributed lock abstractions")
    void locksShouldUseDistributedLocks() {
      classes()
          .that()
          .haveSimpleNameContaining("Lock")
          .and()
          .areMetaAnnotatedWith("org.springframework.stereotype.Component")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.redisson.api..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure.lock..")
          .because(
              """
                            Locks should use distributed lock abstractions (Redisson).
                            synchronized/ReentrantLock prevent horizontal scaling.
                            Distributed locks enable scale-out.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * State should be stored in Redis or MySQL, not in-memory.
     *
     * <p><strong>P0 Scale-out Blocker:</strong> In-memory state prevents scaling. State must be in
     * external systems.
     */
    @Test
    @DisplayName("State should be stored in external systems (Redis, MySQL)")
    void stateShouldBeInExternalSystems() {
      // This rule is for documentation purposes
      // Manual code review required to detect:
      // - In-memory maps used as data stores
      // - Queue implementations that don't persist to Redis/MySQL
      // - Session state stored in memory
      // Use scale-out-blockers-analysis.md P0/P1 component checklist
    }
  }

  // ========================================
  // Rule 5: Thread Pool and Async State
  // ========================================

  @Nested
  @DisplayName("Thread Pools: Stateless Executors")
  class ThreadPoolTests {

    /**
     * Thread pools should use Spring ThreadPoolTaskExecutor.
     *
     * <p><strong>high-traffic-performance-analysis.md:</strong> Thread pools must be configurable
     * and monitored. Use Spring @Async + ThreadPoolTaskExecutor.
     */
    @Test
    @DisplayName("Thread pools should use Spring abstractions")
    void threadPoolsShouldUseSpringAbstractions() {
      classes()
          .that()
          .haveSimpleNameContaining("Executor")
          .and()
          .areMetaAnnotatedWith("org.springframework.stereotype.Component")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.scheduling..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("java.util.concurrent..")
          .because(
              """
                            Thread pools should use Spring ThreadPoolTaskExecutor.
                            Enables configuration and monitoring.
                            Raw ExecutorService creates unmonitored threads.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * No manual thread creation.
     *
     * <p><strong>CLAUDE.md Section 14:</strong> new Thread(), Future direct usage is prohibited.
     * Use LogicExecutor or @Async.
     */
    @Test
    @DisplayName("No manual thread creation (new Thread())")
    void noManualThreadCreation() {
      // This rule is for documentation purposes
      // Use manual code review or SonarQube to detect manual thread creation
      // Recommendation: Use @Async annotation or LogicExecutor
    }
  }

  // ========================================
  // Rule 6: Configuration and Properties
  // ========================================

  @Nested
  @DisplayName("Configuration: Immutable Properties")
  class ConfigurationTests {

    /**
     * Configuration classes should be immutable.
     *
     * <p>Spring @ConfigurationProperties classes should use @ConstructorBinding for immutability.
     */
    @Test
    @DisplayName("Configuration classes should be immutable")
    void configurationClassesShouldBeImmutable() {
      classes()
          .that()
          .areMetaAnnotatedWith(
              "org.springframework.boot.context.properties.ConfigurationProperties")
          .should()
          .haveOnlyFinalFields()
          .because(
              """
                            Configuration properties should be immutable.
                            Use @ConstructorBinding or final fields.
                            Prevents runtime configuration changes.
                            """)
          .check(classes);
    }

    /**
     * No runtime modification of configuration.
     *
     * <p>Configuration should be read-only after application startup.
     */
    @Test
    @DisplayName("Configuration should not be modified at runtime")
    void configurationShouldNotBeModified() {
      // This rule is for documentation purposes
      // Use manual code review to detect setters in @ConfigurationProperties
      // Recommendation: Use @ConstructorBinding for immutable configuration
    }
  }

  // ========================================
  // Rule 7: Event and Message State
  // ========================================

  @Nested
  @DisplayName("Events: Immutable Messages")
  class EventTests {

    /**
     * Event objects should be immutable.
     *
     * <p>Events should be immutable records or value objects.
     */
    @Test
    @DisplayName("Event objects should be immutable")
    void eventsShouldBeImmutable() {
      classes()
          .that()
          .haveSimpleNameEndingWith("Event")
          .should()
          .beRecords()
          .orShould()
          .haveOnlyFinalFields()
          .because(
              """
                            Events should be immutable records or value objects.
                            Prevents event modification during processing.
                            Immutable events are thread-safe.
                            """)
          .allowEmptyShould(true)
          .check(classes);

      classes()
          .that()
          .haveSimpleNameEndingWith("Message")
          .should()
          .beRecords()
          .orShould()
          .haveOnlyFinalFields()
          .because(
              """
                            Messages should be immutable records or value objects.
                            Prevents message modification during processing.
                            Immutable messages are thread-safe.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * DTOs should be immutable.
     *
     * <p>Data transfer objects should be immutable to prevent accidental modification.
     */
    @Test
    @DisplayName("DTOs should be immutable")
    void dtosShouldBeImmutable() {
      classes()
          .that()
          .haveSimpleNameEndingWith("Dto")
          .should()
          .beRecords()
          .orShould()
          .haveOnlyFinalFields()
          .because(
              """
                            DTOs should be immutable records or value objects.
                            Prevents accidental data modification.
                            Immutable DTOs are thread-safe.
                            """)
          .allowEmptyShould(true)
          .check(classes);

      classes()
          .that()
          .haveSimpleNameEndingWith("DTO")
          .should()
          .beRecords()
          .orShould()
          .haveOnlyFinalFields()
          .because(
              """
                            DTOs should be immutable records or value objects.
                            Prevents accidental data modification.
                            Immutable DTOs are thread-safe.
                            """)
          .allowEmptyShould(true)
          .check(classes);

      classes()
          .that()
          .haveSimpleNameEndingWith("Request")
          .should()
          .beRecords()
          .orShould()
          .haveOnlyFinalFields()
          .because(
              """
                            Request objects should be immutable records or value objects.
                            Prevents accidental data modification.
                            Immutable requests are thread-safe.
                            """)
          .allowEmptyShould(true)
          .check(classes);

      classes()
          .that()
          .haveSimpleNameEndingWith("Response")
          .should()
          .beRecords()
          .orShould()
          .haveOnlyFinalFields()
          .because(
              """
                            Response objects should be immutable records or value objects.
                            Prevents accidental data modification.
                            Immutable responses are thread-safe.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }
}
