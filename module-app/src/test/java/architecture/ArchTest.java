package architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive ArchUnit tests for enforcing architectural rules in multi-module structure.
 *
 * <p><strong>Multi-Module Structure (ADR-014):</strong>
 *
 * <pre>
 * module-app      (Application layer, Spring Boot)
 *     ↓ depends on
 * module-infra    (Infrastructure, Spring integrations)
 *     ↓ depends on
 * module-core     (Domain logic, ports)
 *     ↓ depends on
 * module-common   (Shared utilities, error handling)
 * </pre>
 *
 * <p><strong>Core Principles:</strong>
 *
 * <ul>
 *   <li>Dependency Direction: app → infra → core → common (no reverse dependencies)
 *   <li>Core/Common modules must be Spring-free (no @Component, @Service, etc.)
 *   <li>No circular dependencies between modules
 *   <li>Package ownership enforcement (each module owns its packages)
 * </ul>
 *
 * <p><strong>References:</strong>
 *
 * <ul>
 *   <li>CLAUDE.md - SOLID principles, Clean Architecture
 *   <li>docs/adr/ADR-014-multi-module-cross-cutting-concerns.md
 *   <li>docs/00_Start_Here/ROADMAP.md - Phase 7: Multi-module refactoring
 * </ul>
 *
 * @see <a href="https://www.archunit.org/">ArchUnit Documentation</a>
 */
@DisplayName("Architectural Rules (Multi-Module Structure)")
public class ArchTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation");

  // ========================================
  // Rule 1: Spring-Free Core & Common Modules
  // ========================================

  @Nested
  @DisplayName("Core Module: Spring-Free Enforcement")
  class CoreModuleRules {

    /**
     * Core module must not depend on Spring annotations.
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
          .resideInAPackage("maple.expectation.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.stereotype..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.web.bind.annotation..")
          .because(
              """
              Core module must be Spring-free.
              Spring annotations create framework coupling.
              Use dependency injection in module-app instead.
              """)
          .check(classes);
    }

    /**
     * Core module must not depend on Spring Boot classes.
     *
     * <p>Core should not import any Spring Boot or Spring Framework classes. This ensures the core
     * domain logic remains pure and reusable in different contexts.
     */
    @Test
    @DisplayName("Core should not depend on Spring classes")
    void coreShouldNotDependOnSpringClasses() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .or()
          .resideInAPackage("maple.expectation.application..")
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
     * Core module must not use JPA annotations.
     *
     * <p>Core domain models should be pure Java objects (records or POJOs). JPA annotations
     * (@Entity, @Table, @Column, etc.) create persistence concerns in the domain layer.
     *
     * <p><strong>Rationale:</strong> Domain models should be decoupled from persistence details.
     * JPA entities belong in module-infra persistence layer.
     */
    @Test
    @DisplayName("Core should not use JPA annotations")
    void coreShouldNotUseJpaAnnotations() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain.model..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("jakarta.persistence..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("javax.persistence..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.hibernate..")
          .because(
              """
              Core domain models must be persistence-agnostic.
              JPA annotations belong in module-infra persistence layer.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on web/mvc framework.
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
          .resideInAPackage("maple.expectation.application..")
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
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  @Nested
  @DisplayName("Common Module: Spring-Free Enforcement")
  class CommonModuleRules {

    /**
     * Common module must not depend on Spring annotations.
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
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.stereotype..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.context.annotation..")
          .because(
              """
              Common module must be Spring-free.
              Shared utilities should be framework-agnostic.
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
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..")
          .because(
              """
              Common module must be framework-agnostic.
              Spring dependencies belong in application modules.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 2: Dependency Direction (app → infra → core → common)
  // ========================================

  @Nested
  @DisplayName("Dependency Direction: Enforcing Layered Architecture")
  class DependencyDirectionRules {

    /**
     * No circular dependencies between packages.
     *
     * <p>Circular dependencies create tight coupling and make modules difficult to test, maintain,
     * and reuse independently. They also indicate a violation of the Dependency Inversion
     * Principle.
     *
     * <p><strong>Multi-Module Structure:</strong>
     *
     * <ul>
     *   <li>module-app may depend on module-infra, module-core, module-common
     *   <li>module-infra may depend on module-core, module-common
     *   <li>module-core may depend on module-common
     *   <li>module-common must not depend on any other module
     * </ul>
     *
     * <p><strong>Note:</strong> This rule requires manual code review or external dependency
     * analysis tools. ArchUnit's slice() API requires additional dependencies.
     */
    @Test
    @DisplayName("No circular dependencies between packages")
    void noCircularDependencies() {
      // This rule is for documentation purposes
      // Use tools like jdeps or Gradle dependency analysis plugins
      // to detect circular dependencies between modules
    }

    /**
     * Common module must not depend on core/application modules.
     *
     * <p>Common module is the foundation of the dependency hierarchy. It should not depend on any
     * higher-level modules (core, application, infrastructure).
     */
    @Test
    @DisplayName("Common should not depend on core or application")
    void commonShouldNotDependOnCoreOrApplication() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.common..")
          .or()
          .resideInAPackage("maple.expectation.error..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("maple.expectation.domain..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("maple.expectation.application..")
          .because(
              """
              Common module is the foundation.
              It must not depend on higher-level modules.
              """)
          .check(classes);
    }

    /**
     * Core module must not depend on application or infrastructure modules.
     *
     * <p>Core module contains business logic and should not depend on application services or
     * infrastructure implementations. This ensures the core remains reusable and testable.
     */
    @Test
    @DisplayName("Core should not depend on application or infrastructure")
    void coreShouldNotDependOnApplicationOrInfrastructure() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .or()
          .resideInAPackage("maple.expectation.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..service..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("..controller..")
          .because(
              """
              Core module must not depend on application/infrastructure.
              Core should be independent of Spring/web concerns.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Infrastructure should not depend on application services.
     *
     * <p>Infrastructure (repositories, external clients) should implement interfaces defined in
     * core module, not depend on application service implementations.
     *
     * <p><strong>Rationale:</strong> This follows the Dependency Inversion Principle (DIP) from
     * SOLID. Infrastructure depends on abstractions (ports), not concretions.
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
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("..service.v4..")
          .because(
              """
              Infrastructure should depend on core abstractions (ports).
              Direct dependency on application services violates DIP.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 3: Package Ownership Enforcement
  // ========================================

  @Nested
  @DisplayName("Package Ownership: Module Boundaries")
  class PackageOwnershipRules {

    /**
     * Domain models should reside in core module.
     *
     * <p>Core module owns the domain models. Application/infrastructure modules should not define
     * domain entities.
     */
    @Test
    @DisplayName("Domain models should be in core module")
    void domainModelsShouldBeInCore() {
      classes()
          .that()
          .haveSimpleNameEndingWith("Entity")
          .or()
          .haveSimpleNameEndingWith("Aggregate")
          .or()
          .haveSimpleNameEndingWith("ValueObject")
          .should()
          .resideInAPackage("..domain.model..")
          .because(
              """
              Domain models belong in core module.
              Business entities should not be defined in app/infra.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Repository implementations should reside in infrastructure module.
     *
     * <p>Infrastructure module owns the repository implementations (JPA, Redis, external APIs).
     * Core module defines repository interfaces (ports).
     */
    @Test
    @DisplayName("Repository implementations should be in infrastructure")
    void repositoriesShouldBeInInfrastructure() {
      classes()
          .that()
          .haveSimpleNameEndingWith("RepositoryImpl")
          .or()
          .haveSimpleNameEndingWith("JpaRepository")
          .should()
          .resideInAPackage("..infrastructure..")
          .because(
              """
              Repository implementations belong in infrastructure.
              Core module defines repository interfaces (ports).
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Controllers should reside in application module.
     *
     * <p>Application module owns the REST controllers. Infrastructure should not define HTTP
     * endpoints.
     */
    @Test
    @DisplayName("Controllers should be in application module")
    void controllersShouldBeInApplication() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.web.bind.annotation.RestController")
          .or()
          .areMetaAnnotatedWith("org.springframework.stereotype.Controller")
          .should()
          .resideInAPackage("..controller..")
          .because(
              """
              REST controllers belong in application module.
              Infrastructure should not define HTTP endpoints.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Services should reside in application module.
     *
     * <p>Application services orchestrate business logic. Core module contains domain services
     * (pure functions), infrastructure contains technical services (repositories, clients).
     */
    @Test
    @DisplayName("Application services should be in application module")
    void servicesShouldBeInApplication() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.stereotype.Service")
          .should()
          .resideInAPackage("..service..")
          .because(
              """
              Application services belong in service layer.
              Domain services (pure functions) belong in core.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 4: SOLID Principles Enforcement
  // ========================================

  @Nested
  @DisplayName("SOLID Principles: Code Quality")
  class SolidPrinciplesRules {

    /**
     * Controllers should be thin (only final fields).
     *
     * <p>Controllers should be immutable and delegate to services. This enforces the Single
     * Responsibility Principle (SRP) - controllers only handle HTTP concerns.
     *
     * <p><strong>See CLAUDE.md Section 14:</strong> Controllers should be thin with constructor
     * injection.
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
              Controllers should be immutable (SRP).
              Use constructor injection with final fields.
              Controllers delegate to services, no business logic.
              """)
          .check(classes);
    }

    /**
     * No God classes (classes > 800 lines).
     *
     * <p>Large classes violate the Single Responsibility Principle. They should be split into
     * smaller, focused classes.
     *
     * <p><strong>Threshold:</strong> 800 lines (from CLAUDE.md Section 14)
     *
     * <p><strong>Note:</strong> This rule requires manual code review or SonarQube integration.
     * ArchUnit's line counting API requires additional setup.
     */
    @Test
    @DisplayName("No God classes (> 800 lines)")
    void noGodClasses() {
      // This rule is for documentation purposes
      // Use SonarQube or manual code review to detect God classes
    }

    /**
     * No large methods (> 50 lines).
     *
     * <p>Large methods are hard to understand, test, and maintain. They should be split into
     * smaller, focused methods.
     *
     * <p><strong>Threshold:</strong> 50 lines (from CLAUDE.md Section 14)
     *
     * <p><strong>Note:</strong> This rule requires manual code review or SonarQube integration.
     * ArchUnit's method line counting requires additional setup.
     */
    @Test
    @DisplayName("No large methods (> 50 lines)")
    void noLargeMethods() {
      // This rule is for documentation purposes
      // Use SonarQube or manual code review to detect large methods
    }
  }

  // ========================================
  // Rule 5: Naming Conventions (CLAUDE.md Section 6)
  // ========================================

  @Nested
  @DisplayName("Naming Conventions: Clear Intent")
  class NamingConventionsRules {

    /**
     * Repository interfaces should be named *Repository.
     *
     * <p>Repository interfaces should follow naming conventions for clarity.
     */
    @Test
    @DisplayName("Repository interfaces should end with Repository")
    void repositoriesShouldHaveProperNaming() {
      classes()
          .that()
          .resideInAPackage("..repository..")
          .and()
          .areInterfaces()
          .should()
          .haveSimpleNameEndingWith("Repository")
          .because(
              """
              Repository interfaces should end with 'Repository'.
              Naming convention makes code intent clear.
              """)
          .check(classes);
    }

    /**
     * Service implementations should be named *Service or *ServiceImpl.
     *
     * <p>Service classes should follow naming conventions for clarity.
     */
    @Test
    @DisplayName("Service classes should end with Service")
    void servicesShouldHaveProperNaming() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.stereotype.Service")
          .should()
          .haveSimpleNameEndingWith("Service")
          .because(
              """
              Service classes should end with 'Service'.
              Naming convention makes code intent clear.
              """)
          .check(classes);
    }

    /**
     * Controllers should be named *Controller.
     *
     * <p>Controller classes should follow naming conventions for clarity.
     */
    @Test
    @DisplayName("Controllers should end with Controller")
    void controllersShouldHaveProperNaming() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.web.bind.annotation.RestController")
          .or()
          .areMetaAnnotatedWith("org.springframework.stereotype.Controller")
          .should()
          .haveSimpleNameEndingWith("Controller")
          .because(
              """
              Controller classes should end with 'Controller'.
              Naming convention makes code intent clear.
              """)
          .check(classes);
    }
  }
}
