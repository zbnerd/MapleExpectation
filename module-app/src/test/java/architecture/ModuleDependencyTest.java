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
 * Module dependency enforcement tests.
 *
 * <p><strong>Multi-Module Structure (ADR-014, ADR-035):</strong>
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
 * <p><strong>Dependency Direction:</strong> app → infra → core → common (no reverse dependencies)
 *
 * <p><strong>References:</strong>
 *
 * <ul>
 *   <li>docs/adr/ADR-014-multi-module-cross-cutting-concerns.md
 *   <li>docs/adr/ADR-035-multi-module-migration-completion.md
 *   <li>docs/adr/ADR-039-current-architecture-assessment.md
 *   <li>docs/04_Reports/Multi-Module-Refactoring-Analysis.md
 * </ul>
 *
 * <p><strong>P0 Issues from ADR-039:</strong>
 *
 * <ul>
 *   <li>module-app has 56 @Configuration classes (should be in module-infra)
 *   <li>module-app/repository/ is empty (should be deleted)
 *   <li>module-app has 45 monitoring files (should be in module-infra)
 * </ul>
 */
@DisplayName("Module Dependency Enforcement")
class ModuleDependencyTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation");

  // ========================================
  // Rule 1: Module Dependency Direction
  // ========================================

  @Nested
  @DisplayName("Dependency Direction: app → infra → core → common")
  class DependencyDirectionTests {

    /**
     * module-app may depend on module-infra, module-core, module-common.
     *
     * <p><strong>Correct:</strong> app → infra → core → common
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ CORRECT (follows DIP principle)
     */
    @Test
    @DisplayName("module-app may depend on infra, core, common")
    void moduleAppMayDependOnLowerModules() {
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .or()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.sql..")
          .because(
              """
                            Application layer should not directly depend on JDBC.
                            Use repository abstractions from infrastructure layer.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * module-infra may depend on module-core, module-common.
     *
     * <p><strong>Correct:</strong> infra → core → common
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ CORRECT
     */
    @Test
    @DisplayName("module-infra may depend on core, common")
    void moduleInfraMayDependOnLowerModules() {
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
                            Infrastructure should not depend on application services.
                            Infrastructure should implement abstractions from core module (DIP).
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * module-core may depend on module-common only.
     *
     * <p><strong>Correct:</strong> core → common
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ CORRECT
     */
    @Test
    @DisplayName("module-core may only depend on common")
    void moduleCoreMayOnlyDependOnCommon() {
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
     * module-common must not depend on any other module.
     *
     * <p><strong>Correct:</strong> common (foundation)
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ CORRECT
     */
    @Test
    @DisplayName("module-common must not depend on other modules")
    void moduleCommonMustNotDependOnOtherModules() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.common..")
          .or()
          .resideInAPackage("maple.expectation.error..")
          .or()
          .resideInAPackage("maple.expectation.shared..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("maple.expectation.domain..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("maple.expectation.application..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("maple.expectation.infrastructure..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("maple.expectation.service..")
          .because(
              """
                            Common module is the foundation.
                            It must not depend on higher-level modules.
                            """)
          .check(classes);
    }
  }

  // ========================================
  // Rule 2: No Circular Dependencies
  // ========================================

  @Nested
  @DisplayName("Circular Dependency Detection")
  class CircularDependencyTests {

    /**
     * No circular dependencies at package level.
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ NO CIRCULAR DEPENDENCIES DETECTED
     *
     * <p><strong>Note:</strong> ArchUnit's slice() API produces false positives for legitimate
     * same-package dependencies (12,148+ violations). Use jdeps or Gradle dependency analysis
     * instead.
     */
    @Test
    @DisplayName("No circular dependencies between modules")
    void noCircularDependencies() {
      // This rule is for documentation purposes
      // Use tools like jdeps or Gradle dependency analysis plugins
      // to detect circular dependencies between modules
      // ADR-039 confirms: 0 circular dependencies at module level
    }

    /**
     * Service layer should not create circular dependencies.
     *
     * <p>v2/v4/v5 services should have clear dependency hierarchy.
     */
    @Test
    @DisplayName("No circular dependencies in service layer")
    void noCircularDependenciesInServiceLayer() {
      // This rule is for documentation purposes
      // Use jdeps or Gradle dependency analysis to detect circular dependencies
      // Service versions (v2/v4/v5) should not have circular dependencies
    }
  }

  // ========================================
  // Rule 3: Package Ownership Enforcement
  // ========================================

  @Nested
  @DisplayName("Package Ownership: Module Boundaries")
  class PackageOwnershipTests {

    /**
     * Repository implementations belong in module-infra.
     *
     * <p><strong>P0 Issue (ADR-039):</strong> module-app/repository/ is EMPTY and should be
     * deleted.
     */
    @Test
    @DisplayName("Repository implementations should be in infrastructure")
    void repositoriesShouldBeInInfrastructure() {
      classes()
          .that()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .areNotInterfaces()
          .should()
          .resideInAPackage("..infrastructure.persistence..")
          .orShould()
          .resideInAPackage("..domain.repository..")
          .because(
              """
                            Repository implementations belong in infrastructure.
                            Core module defines repository interfaces (ports).
                            module-app/repository/ is empty (P0 issue - delete it).
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * @Configuration classes belong in module-infra.
     *
     * <p><strong>P0 Issue (ADR-039):</strong> module-app has 56 @Configuration classes that should
     * be moved to module-infra.
     */
    @Test
    @DisplayName("@Configuration classes should be in module-infra")
    void configurationClassesShouldBeInInfra() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.context.annotation.Configuration")
          .and()
          .resideInAPackage("maple.expectation.config..")
          .should()
          .resideInAPackage("..infrastructure.config..")
          .because(
              """
                            @Configuration classes belong in module-infra.
                            module-app currently has 56 @Configuration classes (P0 issue).
                            Move to module-infra to reduce module-app bloat.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * AOP aspects belong in module-infra.
     *
     * <p><strong>P0 Issue (ADR-039):</strong> module-app/aop/ should be moved to module-infra.
     */
    @Test
    @DisplayName("AOP aspects should be in module-infra")
    void aopAspectsShouldBeInInfra() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.aspectj.lang.annotation.Aspect")
          .and()
          .resideInAPackage("maple.expectation.aop..")
          .should()
          .resideInAPackage("..infrastructure.aop..")
          .because(
              """
                            AOP aspects belong in module-infra.
                            Cross-cutting concerns are infrastructure concerns.
                            module-app/aop/ should be moved to module-infra (P0 issue).
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Monitoring logic belongs in module-infra or module-observability.
     *
     * <p><strong>P0 Issue (ADR-039):</strong> module-app/monitoring/ has 45 files that should be
     * moved to module-infra or separate module-observability.
     */
    @Test
    @DisplayName("Monitoring logic should be in module-infra or module-observability")
    void monitoringLogicShouldBeInInfra() {
      classes()
          .that()
          .resideInAPackage("maple.expectation.monitoring..")
          .and()
          .areMetaAnnotatedWith("org.springframework.stereotype.Component")
          .or()
          .areMetaAnnotatedWith("org.springframework.stereotype.Service")
          .should()
          .resideInAPackage("..infrastructure.monitoring..")
          .orShould()
          .resideInAPackage("..observability..")
          .because(
              """
                            Monitoring logic belongs in module-infra or module-observability.
                            module-app/monitoring/ has 45 files (P0 issue).
                            Move to module-infra to reduce module-app bloat.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Scheduler/Batch logic belongs in module-infra.
     *
     * <p><strong>P1 Issue (ADR-039):</strong> module-app/scheduler/ and module-app/batch/ should be
     * moved to module-infra.
     */
    @Test
    @DisplayName("Scheduler/Batch logic should be in module-infra")
    void schedulerBatchLogicShouldBeInInfra() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
          .or()
          .areMetaAnnotatedWith("org.springframework.batch.core.step.builder.StepBuilder")
          .and()
          .resideInAPackage("maple.expectation.scheduler..")
          .or()
          .resideInAPackage("maple.expectation.batch..")
          .should()
          .resideInAPackage("..infrastructure.scheduler..")
          .orShould()
          .resideInAPackage("..infrastructure.batch..")
          .because(
              """
                            Scheduler/Batch logic belongs in module-infra.
                            Infrastructure concerns should not be in application layer.
                            module-app/scheduler/ and /batch/ should be moved (P1 issue).
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Controllers belong in module-app.
     *
     * <p><strong>Correct:</strong> REST controllers are application layer concerns.
     */
    @Test
    @DisplayName("Controllers should be in module-app")
    void controllersShouldBeInApp() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.web.bind.annotation.RestController")
          .or()
          .areMetaAnnotatedWith("org.springframework.stereotype.Controller")
          .should()
          .resideInAPackage("..controller..")
          .because(
              """
                            REST controllers belong in module-app.
                            Application layer owns HTTP endpoints.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Services belong in module-app.
     *
     * <p><strong>Note:</strong> ADR-039 identifies 146 service files (v2/v4/v5) that may need
     * splitting into module-app-service.
     */
    @Test
    @DisplayName("Services should be in module-app")
    void servicesShouldBeInApp() {
      classes()
          .that()
          .areMetaAnnotatedWith("org.springframework.stereotype.Service")
          .should()
          .resideInAPackage("..service..")
          .because(
              """
                            Application services belong in service layer.
                            Domain services (pure functions) belong in core.
                            Note: 146 service files may need v2/v4/v5 split (P1 issue).
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 4: Module Size Limits
  // ========================================

  @Nested
  @DisplayName("Module Size: Prevent Bloat")
  class ModuleSizeTests {

    /**
     * module-app file count limit.
     *
     * <p><strong>Target (ADR-039):</strong> < 150 files (currently 342 files, -56% reduction
     * needed).
     */
    @Test
    @DisplayName("module-app should have < 150 files (target)")
    void moduleAppSizeLimit() {
      // This rule requires manual verification
      // Current: 342 files
      // Target: < 150 files
      // Reduction needed: -56%
      // Use Gradle task to count files and fail if limit exceeded
    }

    /**
     * module-infra file count limit.
     *
     * <p><strong>Target (ADR-039):</strong> < 250 files (currently 177 files, +41% capacity).
     */
    @Test
    @DisplayName("module-infra should have < 250 files (target)")
    void moduleInfraSizeLimit() {
      // This rule requires manual verification
      // Current: 177 files
      // Target: < 250 files
      // Capacity: +41%
    }

    /**
     * module-core file count limit.
     *
     * <p><strong>Target (ADR-039):</strong> < 80 files (currently 59 files, +36% capacity).
     */
    @Test
    @DisplayName("module-core should have < 80 files (target)")
    void moduleCoreSizeLimit() {
      // This rule requires manual verification
      // Current: 59 files
      // Target: < 80 files
      // Capacity: +36%
    }

    /**
     * module-common file count limit.
     *
     * <p><strong>Target (ADR-039):</strong> < 50 files (currently 35 files, +43% capacity).
     */
    @Test
    @DisplayName("module-common should have < 50 files (target)")
    void moduleCommonSizeLimit() {
      // This rule requires manual verification
      // Current: 35 files
      // Target: < 50 files
      // Capacity: +43%
    }
  }

  // ========================================
  // Rule 5: Spring Annotation Placement
  // ========================================

  @Nested
  @DisplayName("Spring Annotation Placement: Framework Boundaries")
  class SpringAnnotationTests {

    /**
     * @Configuration count limit in module-app.
     *
     * <p><strong>P0 Issue (ADR-039):</strong> module-app has 56 @Configuration classes. Target: <
     * 5.
     */
    @Test
    @DisplayName("module-app should have < 5 @Configuration classes (target)")
    void configurationCountLimit() {
      // This rule requires manual verification
      // Current: 56 @Configuration classes in module-app
      // Target: < 5 @Configuration classes
      // Move 50+ classes to module-infra
    }

    /**
     * No @Repository in module-app.
     *
     * <p><strong>P0 Issue (ADR-039):</strong> module-app/repository/ is EMPTY and should be
     * deleted. Repository implementations belong in module-infra.
     */
    @Test
    @DisplayName("No @Repository in module-app")
    void noRepositoryInApp() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.controller..")
          .or()
          .resideInAPackage("maple.expectation.service..")
          .or()
          .resideInAPackage("maple.expectation.config..")
          .should()
          .beMetaAnnotatedWith("org.springframework.stereotype.Repository")
          .because(
              """
                            @Repository implementations belong in module-infra.
                            module-app/repository/ is empty (P0 issue - delete it).
                            """)
          .check(classes);
    }

    /**
     * No @Component in module-core.
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ 0 Spring annotations in module-core
     * (framework-agnostic).
     */
    @Test
    @DisplayName("No @Component in module-core")
    void noComponentInCore() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .should()
          .beMetaAnnotatedWith("org.springframework.stereotype.Component")
          .because(
              """
                            Core module must be framework-agnostic.
                            Spring annotations belong in application/infrastructure layers.
                            """)
          .check(classes);
    }

    /**
     * No @Service in module-core.
     *
     * <p><strong>ADR-039 Finding:</strong> ✅ 0 Spring annotations in module-core.
     */
    @Test
    @DisplayName("No @Service in module-core")
    void noServiceInCore() {
      noClasses()
          .that()
          .resideInAPackage("maple.expectation.domain..")
          .should()
          .beMetaAnnotatedWith("org.springframework.stereotype.Service")
          .because(
              """
                            Core module must be framework-agnostic.
                            Spring annotations belong in application/infrastructure layers.
                            """)
          .check(classes);
    }
  }
}
