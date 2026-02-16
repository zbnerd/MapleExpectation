package maple.expectation.config;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.calculator.CubeRateCalculator;
import maple.expectation.core.calculator.PotentialCalculator;
import maple.expectation.core.domain.model.AlertMessage;
import maple.expectation.core.domain.model.AlertPriority;
import maple.expectation.core.domain.model.CharacterId;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;
import maple.expectation.core.domain.model.PotentialStat;
import maple.expectation.core.domain.stat.StatParser;
import maple.expectation.core.port.out.AlertPort;
import maple.expectation.core.port.out.CubeRatePort;
import maple.expectation.core.port.out.EquipmentDataPort;
import maple.expectation.core.port.out.ItemPricePort;
import maple.expectation.core.port.out.PotentialStatPort;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.model.equipment.EquipmentData;
import maple.expectation.domain.repository.CharacterEquipmentRepository;
import maple.expectation.domain.repository.CubeProbabilityRepository;
import maple.expectation.domain.v2.CubeProbability;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporary Port Adapters
 *
 * <p>⚠️ <b>PHASE 2-B TEMPORARY SOLUTION</b> - Will be replaced in Phase 3 by proper module-infra
 * adapters.
 *
 * <h3>Purpose</h3>
 *
 * <p>Bridges Phase 2-A port interfaces to existing repository implementations during the transition
 * to hexagonal architecture. This allows core domain to depend on ports while we gradually migrate
 * implementations to module-infra.
 *
 * <h3>Migration Plan (Phase 3)</h3>
 *
 * <ol>
 *   <li>Create adapter implementations in module-infra (e.g., {@code CubeRateRepositoryAdapter})
 *   <li>Move repository-dependent logic from this config to dedicated adapter classes
 *   <li>Delete this configuration class
 * </ol>
 *
 * <h3>Current Adapter Mappings</h3>
 *
 * <ul>
 *   <li>{@link CubeRatePort} → {@link CubeProbabilityRepository}
 *   <li>{@link EquipmentDataPort} → {@link CharacterEquipmentRepository}
 *   <li>{@link PotentialStatPort} → Mock implementation (to be implemented)
 *   <li>{@link AlertPort} → Mock implementation (to be implemented)
 *   <li>{@link ItemPricePort} → Mock implementation (to be implemented)
 * </ul>
 *
 * @see maple.expectation.core.port.out
 * @see maple.expectation.domain.repository
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TemporaryAdapterConfig {

  private final CubeProbabilityRepository cubeProbabilityRepository;
  private final CharacterEquipmentRepository characterEquipmentRepository;
  private final StatParser statParser;

  /**
   * Core Calculator Beans
   *
   * <p>Registers core layer calculators as Spring beans for dependency injection.
   *
   * <p><b>Phase 3:</b> Move to module-core configuration when core becomes self-contained.
   */
  @Bean
  public CubeRateCalculator cubeRateCalculator() {
    log.info("[TemporaryAdapter] Initializing CubeRateCalculator bean");
    return new CubeRateCalculator();
  }

  @Bean
  public PotentialCalculator potentialCalculator() {
    log.info("[TemporaryAdapter] Initializing PotentialCalculator bean");
    return new PotentialCalculator(statParser);
  }

  /**
   * CubeRate Port Adapter
   *
   * <p>Maps legacy {@link CubeProbability} entities to core {@link CubeRate} domain models.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.CubeRateRepositoryAdapter}
   */
  @Bean
  public CubeRatePort cubeRatePort() {
    log.info("[TemporaryAdapter] Initializing CubeRatePort -> CubeProbabilityRepository bridge");

    return new CubeRatePort() {
      @Override
      public List<CubeRate> findByCubeType(CubeType type) {
        return cubeProbabilityRepository.findAll().stream()
            .filter(p -> p.getCubeType().name().equals(type.name()))
            .map(
                p ->
                    new CubeRate(
                        mapToCoreCubeType(p.getCubeType()),
                        p.getOptionName(),
                        p.getRate(),
                        p.getSlot(),
                        p.getGrade(),
                        p.getLevel(),
                        p.getPart()))
            .toList();
      }

      @Override
      public List<CubeRate> findAll() {
        return cubeProbabilityRepository.findAll().stream()
            .map(
                p ->
                    new CubeRate(
                        mapToCoreCubeType(p.getCubeType()),
                        p.getOptionName(),
                        p.getRate(),
                        p.getSlot(),
                        p.getGrade(),
                        p.getLevel(),
                        p.getPart()))
            .toList();
      }
    };
  }

  /**
   * EquipmentData Port Adapter
   *
   * <p>Maps between core {@link EquipmentData} and legacy {@link CharacterEquipment} domain models.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.EquipmentDataRepositoryAdapter}
   */
  @Bean
  public EquipmentDataPort equipmentDataPort() {
    log.info(
        "[TemporaryAdapter] Initializing EquipmentDataPort -> CharacterEquipmentRepository bridge");

    return new EquipmentDataPort() {
      @Override
      public Optional<EquipmentData> findByCharacterId(CharacterId characterId) {
        return characterEquipmentRepository
            .findById(mapToLegacyCharacterId(characterId))
            .map(CharacterEquipment::equipmentData);
      }

      @Override
      public Optional<EquipmentData> findByOcid(String ocid) {
        return characterEquipmentRepository
            .findById(maple.expectation.domain.model.character.CharacterId.of(ocid))
            .map(CharacterEquipment::equipmentData);
      }

      @Override
      public void save(CharacterId characterId, EquipmentData equipmentData) {
        CharacterEquipment legacy =
            CharacterEquipment.restore(
                mapToLegacyCharacterId(characterId), equipmentData, java.time.LocalDateTime.now());
        characterEquipmentRepository.save(legacy);
      }

      @Override
      public void deleteByCharacterId(CharacterId characterId) {
        characterEquipmentRepository.deleteById(mapToLegacyCharacterId(characterId));
      }
    };
  }

  /**
   * PotentialStat Port Adapter (Mock)
   *
   * <p>⚠️ <b>TODO:</b> Implement actual data source for potential stats.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.PotentialStatRepositoryAdapter}
   */
  @Bean
  public PotentialStatPort potentialStatPort() {
    log.warn("[TemporaryAdapter] PotentialStatPort using MOCK implementation");

    return new PotentialStatPort() {
      @Override
      public Optional<PotentialStat> findByOptionName(String optionName) {
        // TODO: Implement actual lookup from data source
        return Optional.empty();
      }

      @Override
      public boolean isValidOption(String optionName) {
        // TODO: Implement actual validation
        return false;
      }
    };
  }

  /**
   * Alert Port Adapter (Mock)
   *
   * <p>⚠️ <b>TODO:</b> Integrate with actual alerting system (Discord, email, etc.).
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.AlertNotificationAdapter}
   */
  @Bean
  public AlertPort alertPort() {
    log.warn("[TemporaryAdapter] AlertPort using MOCK implementation");

    return new AlertPort() {
      @Override
      public boolean sendAlert(AlertMessage message) {
        log.info("[Mock Alert] {}", message);
        return true;
      }

      @Override
      public boolean sendAlert(AlertMessage message, AlertPriority priority) {
        log.info("[Mock Alert] [{}] {}", priority, message);
        return true;
      }
    };
  }

  /**
   * ItemPrice Port Adapter (Mock)
   *
   * <p>⚠️ <b>TODO:</b> Integrate with Nexon API for item price data.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.NexonItemPriceAdapter}
   */
  @Bean
  public ItemPricePort itemPricePort() {
    log.warn("[TemporaryAdapter] ItemPricePort using MOCK implementation");

    return new ItemPricePort() {
      @Override
      public Optional<maple.expectation.core.domain.model.ItemPrice> findByItemId(Long itemId) {
        // TODO: Implement actual Nexon API integration
        return Optional.empty();
      }

      @Override
      public Optional<maple.expectation.core.domain.model.ItemPrice> findByItemName(
          String itemName) {
        // TODO: Implement actual Nexon API integration
        return Optional.empty();
      }
    };
  }

  // ========== Mapping Helper Methods ==========

  private static maple.expectation.domain.model.character.CharacterId mapToLegacyCharacterId(
      CharacterId coreId) {
    return maple.expectation.domain.model.character.CharacterId.of(coreId.value());
  }

  private static maple.expectation.core.domain.model.CubeType mapToCoreCubeType(
      maple.expectation.domain.v2.CubeType legacyType) {
    return maple.expectation.core.domain.model.CubeType.valueOf(legacyType.name());
  }
}
