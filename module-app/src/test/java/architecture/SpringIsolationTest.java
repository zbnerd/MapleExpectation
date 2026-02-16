package architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Spring framework isolation tests.
 *
 * <p><strong>Core Principle:</strong> Core and Common modules must be framework-agnostic to enable:
 *
 * <ul>
 *   <li>Independent testing without Spring context
 *   <li>Reuse in different contexts (not just Spring)
 *   <li>Clear separation of domain logic from infrastructure
 *   <li>Prevention of architectural drift
 * </ul>
 *
 * <p><strong>References:</strong>
 *
 * <ul>
 *   <li>docs/adr/ADR-039-current-architecture-assessment.md - Framework-agnostic core
 *   <li>docs/04_Reports/Multi-Module-Refactoring-Analysis.md - Spring annotation analysis
 *   <li>CLAUDE.md - Section 4: Implementation Logic & SOLID
 * </ul>
 *
 * <p><strong>ADR-039 Findings:</strong>
 *
 * <ul>
 *   <li>module-core: ✅ 0 Spring annotations (framework-agnostic)
 *   <li>module-common: ⚠️ 1 Spring annotation found (needs investigation)
 *   <li>module-app: 228 Spring annotations (expected - it's the Spring Boot app)
 *   <li>module-infra: 70 Spring annotations (expected - infrastructure implementations)
 * </ul>
 */
@DisplayName("Spring Framework Isolation Tests")
class SpringIsolationTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation");

  // ========================================
  // Rule 1: Core Module Spring-Free
  // ========================================

  @Nested
  @DisplayName("Core Module: Framework-Agnostic Enforcement")
  class CoreModuleSpringFreeTests {

    /**
     * Core module must not use Spring annotations.
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ 0 Spring annotations in module-core
     *
     * <p>Core module contains pure business logic and should be framework-agnostic. Spring
     * annotations (@Component, @Service, @Repository, etc.) create tight coupling to the framework.
     *
     * <p><strong>Rationale:</strong> Core domain logic should be testable without Spring context.
     * Framework dependencies belong in module-app or module-infra.
     */
    @Test
    @DisplayName("Core should not use Spring annotations")
    void coreShouldNotUseSpringAnnotations() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .or()
          .resideInAPackage("maple.expectation.application.port..")
          .should()
          .beMetaAnnotatedWith("org.springframework..")
          .because(
              """
                            Core module must be Spring-free.
                            Spring annotations create framework coupling.
                            Core should be testable without Spring context.
                            """)
          .check(classes);
    }

    /**
     * Core module must not depend on Spring classes.
     *
     * <p>Core should not import any Spring Boot or Spring Framework classes. This ensures the core
     * domain logic remains pure and reusable in different contexts.
     */
    @Test
    @DisplayName("Core should not depend on Spring Framework classes")
    void coreShouldNotDependOnSpringFramework() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .or()
          .resideInAPackage("maple.expectation.application.port..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..")
          .because(
              """
                            Core module must be framework-agnostic.
                            Spring dependencies belong in module-app or module-infra.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core models must not use JPA annotations.
     *
     * <p>Core domain models should be pure Java objects (records or POJOs). JPA annotations
     * (@Entity, @Table, @Column, etc.) create persistence concerns in the domain layer.
     *
     * <p><strong>Rationale:</strong> Domain models should be decoupled from persistence details.
     * JPA entities belong in module-infra persistence layer.
     */
    @Test
    @DisplayName("Core models should not use JPA annotations")
    void coreModelsShouldNotUseJpaAnnotations() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain.model..")
          .should()
          .beMetaAnnotatedWith("jakarta.persistence..")
          .orShould()
          .beMetaAnnotatedWith("javax.persistence..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.hibernate..")
          .because(
              """
                            Core domain models must be persistence-agnostic.
                            JPA annotations belong in module-infra persistence layer.
                            Domain models should be pure Java objects (records/POJOs).
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module must not depend on web/mvc framework.
     *
     * <p>Core should not import servlet, web, or HTTP-related classes. This ensures the core logic
     * is not tied to any specific protocol or transport layer.
     */
    @Test
    @DisplayName("Core should not depend on web framework")
    void coreShouldNotDependOnWebFramework() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .or()
          .resideInAPackage("maple.expectation.application.port..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("jakarta.servlet..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("javax.servlet..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.web..")
          .because(
              """
                            Core module must be protocol-agnostic.
                            Web concerns belong in module-app (controllers).
                            Core logic should not depend on HTTP/servlet APIs.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module must not depend on Spring Data.
     *
     * <p>Core should not import Spring Data repositories or JPA entities. This ensures core logic
     * is independent of persistence technology.
     */
    @Test
    @DisplayName("Core should not depend on Spring Data")
    void coreShouldNotDependOnSpringData() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.data..")
          .because(
              """
                            Core module must be persistence-agnostic.
                            Spring Data belongs in module-infra persistence layer.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module must not depend on Spring Security.
     *
     * <p>Core should not import security-related Spring classes. Security is an infrastructure
     * concern.
     */
    @Test
    @DisplayName("Core should not depend on Spring Security")
    void coreShouldNotDependOnSpringSecurity() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.security..")
          .because(
              """
                            Core module must be security-agnostic.
                            Spring Security belongs in module-infra security layer.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module must not depend on Actuator/Observability.
     *
     * <p>Monitoring and metrics are infrastructure concerns, not domain logic.
     */
    @Test
    @DisplayName("Core should not depend on Actuator/Micrometer")
    void coreShouldNotDependOnActuator() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.boot.actuator..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("io.micrometer..")
          .because(
              """
                            Core module must be monitoring-agnostic.
                            Actuator/Micrometer belong in module-infra monitoring layer.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 2: Common Module Spring-Free
  // ========================================

  @Nested
  @DisplayName("Common Module: Framework-Agnostic Enforcement")
  class CommonModuleSpringFreeTests {

    /**
     * Common module must not use Spring annotations.
     *
     * <p><strong>ADR-039 Finding:</strong> ⚠️ 1 Spring annotation found (needs investigation)
     *
     * <p>Common module contains shared utilities (error handling, common types) and should be
     * usable in any context, not just Spring applications.
     *
     * <p><strong>Rationale:</strong> Common utilities should be framework-agnostic. Spring
     * annotations create unnecessary coupling.
     */
    @Test
    @DisplayName("Common should not use Spring annotations")
    void commonShouldNotUseSpringAnnotations() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.common..")
          .or()
          .resideInAPackage("maple.expectation.error..")
          .or()
          .resideInAPackage("maple.expectation.shared..")
          .or()
          .resideInAPackage("maple.expectation.util..")
          .should()
          .beMetaAnnotatedWith("org.springframework.stereotype..")
          .orShould()
          .beMetaAnnotatedWith("org.springframework.context.annotation..")
          .because(
              """
                            Common module must be Spring-free.
                            Shared utilities should be framework-agnostic.
                            Investigate: 1 annotation found in ADR-039 analysis.
                            """)
          .check(classes);
    }

    /**
     * Common module must not depend on Spring classes.
     *
     * <p>Common module should not import Spring Framework classes. This ensures utilities can be
     * reused in different contexts.
     */
    @Test
    @DisplayName("Common should not depend on Spring classes")
    void commonShouldNotDependOnSpringClasses() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.common..")
          .or()
          .resideInAPackage("maple.expectation.error..")
          .or()
          .resideInAPackage("maple.expectation.shared..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..")
          .because(
              """
                            Common module must be framework-agnostic.
                            Spring dependencies belong in application modules.
                            Utilities should be reusable in any context.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Error handling must be framework-agnostic.
     *
     * <p>Exception classes should not depend on Spring. They should be pure Java exceptions that
     * can be used in any context.
     */
    @Test
    @DisplayName("Error handling should be framework-agnostic")
    void errorHandlingShouldBeFrameworkAgnostic() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.error..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..")
          .because(
              """
                            Error handling must be framework-agnostic.
                            Exceptions should be usable in any context.
                            Spring-specific error handling belongs in module-app (GlobalExceptionHandler).
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Response DTOs must be framework-agnostic.
     *
     * <p>Response classes should not depend on Spring annotations like @JsonIgnore or Jackson
     * configuration unless necessary.
     */
    @Test
    @DisplayName("Response DTOs should be framework-agnostic")
    void responseDtosShouldBeFrameworkAgnostic() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.response..")
          .should()
          .beMetaAnnotatedWith("org.springframework..")
          .because(
              """
                            Response DTOs should be framework-agnostic.
                            Minimal framework coupling (Jackson annotations acceptable).
                            Spring annotations belong in application layer.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 3: Infrastructure Module Spring Usage
  // ========================================

  @Nested
  @DisplayName("Infrastructure Module: Appropriate Spring Usage")
  class InfrastructureModuleSpringTests {

    /**
     * Infrastructure may use Spring annotations (expected).
     *
     * <p><strong>ADR-039 Finding:</strong> 70 Spring annotations in module-infra (expected)
     *
     * <p>Infrastructure module is responsible for Spring integration (Redis, JPA, messaging, etc.).
     * Spring annotations are appropriate here.
     *
     * <p>This test documents the expected behavior rather than enforcing restrictions.
     */
    @Test
    @DisplayName("Infrastructure may use Spring annotations (expected)")
    void infrastructureMayUseSpringAnnotations() {
      // This is documentation - infrastructure SHOULD use Spring annotations
      // 70 Spring annotations found in ADR-039 (expected for infrastructure)
      // Examples: @Component, @Repository, @Configuration
    }

    /**
     * Infrastructure should not depend on application services.
     *
     * <p>Infrastructure (repositories, external clients) should implement interfaces defined in
     * core module, not depend on application service implementations.
     *
     * <p><strong>Rationale:</strong> This follows the Dependency Inversion Principle (DIP).
     * Infrastructure depends on abstractions (ports), not concretions.
     */
    @Test
    @DisplayName("Infrastructure should not depend on application services")
    void infrastructureShouldNotDependOnApplicationServices() {
      noClasses()
          .that()
          .resideInAPackage("..infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..service.v2..")
          .because(
              """
                            Infrastructure should depend on core abstractions (ports).
                            Direct dependency on application services violates DIP.
                            Infrastructure implements interfaces, not uses services.
                            """)
          .allowEmptyShould(true)
          .check(classes);

      noClasses()
          .that()
          .resideInAPackage("..infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..service.v4..")
          .because(
              """
                            Infrastructure should depend on core abstractions (ports).
                            Direct dependency on application services violates DIP.
                            Infrastructure implements interfaces, not uses services.
                            """)
          .allowEmptyShould(true)
          .check(classes);

      noClasses()
          .that()
          .resideInAPackage("..infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..service.v5..")
          .because(
              """
                            Infrastructure should depend on core abstractions (ports).
                            Direct dependency on application services violates DIP.
                            Infrastructure implements interfaces, not uses services.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 4: Application Module Spring Usage
  // ========================================

  @Nested
  @DisplayName("Application Module: Appropriate Spring Usage")
  class ApplicationModuleSpringTests {

    /**
     * Application module uses Spring Boot (expected).
     *
     * <p><strong>ADR-039 Finding:</strong> 228 Spring annotations in module-app (expected)
     *
     * <p>Application module is the Spring Boot application. Spring annotations are expected and
     * appropriate here.
     *
     * <p>This test documents the expected behavior.
     */
    @Test
    @DisplayName("Application module uses Spring Boot (expected)")
    void applicationModuleUsesSpringBoot() {
      // This is documentation - application module SHOULD use Spring annotations
      // 228 Spring annotations found in ADR-039 (expected for Spring Boot app)
      // Examples: @RestController, @Service, @Configuration, @Scheduled
    }

    /**
     * Application should not depend on low-level infrastructure details.
     *
     * <p>Application services should depend on abstractions (ports) from core module, not concrete
     * implementations from infrastructure.
     */
    @Test
    @DisplayName("Application should depend on abstractions, not concretions")
    void applicationShouldDependOnAbstractions() {
      // This rule is for documentation purposes
      // Use manual code review to detect direct dependencies on concrete implementations
      // Recommendation: Application services should depend on repository interfaces
    }
  }
}
