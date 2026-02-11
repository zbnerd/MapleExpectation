package maple.expectation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture tests for enforcing clean architecture principles.
 *
 * <p>These tests verify that the codebase follows the hexagonal architecture pattern.
 *
 * <p><b>Note:</b> During ADR-017 migration, these rules only apply to the Equipment slice. Other
 * slices (Character, Calculator, etc.) still use the old structure and are excluded from these
 * rules.
 */
@DisplayName("Clean Architecture Rules (ADR-017 Equipment Slice)")
public class CleanArchitectureTest {

  private final JavaClasses classes =
      new com.tngtech.archunit.core.importer.ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation");

  @Test
  @DisplayName("[Equipment Slice] Domain model should not depend on infrastructure")
  void equipmentDomainModelShouldNotDependOnInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("..domain.model.equipment..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..infrastructure..")
        .because("Equipment domain models must be pure Java")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] Domain model should not use JPA annotations")
  void equipmentDomainModelShouldNotUseJpa() {
    noClasses()
        .that()
        .resideInAPackage("..domain.model.equipment..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("jakarta.persistence..")
        .because("Equipment domain models must not have JPA annotations")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] Domain model should not use Spring")
  void equipmentDomainModelShouldNotUseSpring() {
    noClasses()
        .that()
        .resideInAPackage("..domain.model.equipment..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .because("Equipment domain models must be framework-agnostic")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] Domain model should not use Lombok")
  void equipmentDomainModelShouldNotUseLombok() {
    noClasses()
        .that()
        .resideInAPackage("..domain.model.equipment..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("lombok..")
        .because("Equipment domain models use Java records (no Lombok)")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] JPA entity should be in infrastructure")
  void equipmentJpaEntityShouldBeInInfrastructure() {
    classes()
        .that()
        .haveSimpleName("CharacterEquipmentJpaEntity")
        .should()
        .resideInAPackage("..infrastructure.persistence.entity..")
        .because("Equipment JPA entity belongs to infrastructure layer")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] Repository port should be in domain")
  void equipmentRepositoryPortShouldBeInDomain() {
    classes()
        .that()
        .haveSimpleName("CharacterEquipmentRepository")
        .and()
        .resideInAPackage("..domain.repository..")
        .should()
        .beInterfaces()
        .because("Equipment repository port is a domain interface")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] Repository adapter should be in infrastructure")
  void equipmentRepositoryAdapterShouldBeInInfrastructure() {
    classes()
        .that()
        .haveSimpleName("CharacterEquipmentRepositoryImpl")
        .should()
        .resideInAPackage("..infrastructure.persistence.repository..")
        .because("Equipment repository adapter is infrastructure implementation")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] Mapper should be in infrastructure")
  void equipmentMapperShouldBeInInfrastructure() {
    classes()
        .that()
        .haveSimpleName("CharacterEquipmentMapper")
        .should()
        .resideInAPackage("..infrastructure.persistence.mapper..")
        .because("Equipment mapper is infrastructure concern")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  @DisplayName("[Equipment Slice] Application service should coordinate domain and infrastructure")
  void equipmentApplicationServiceShouldBeInApplicationLayer() {
    classes()
        .that()
        .haveSimpleName("EquipmentApplicationService")
        .should()
        .resideInAPackage("..application.service..")
        .because("Equipment application service orchestrates use cases")
        .check(classes);
  }
}
