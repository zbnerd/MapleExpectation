package maple.expectation.service.v4;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.cost.CostFormatter;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.error.exception.StarforceNotInitializedException;
import maple.expectation.infrastructure.aop.annotation.TraceLog;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.provider.EquipmentDataProvider;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import maple.expectation.service.v2.starforce.StarforceLookupTable;
import maple.expectation.service.v4.cache.ExpectationCacheCoordinator;
import maple.expectation.service.v4.persistence.ExpectationPersistenceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V4 장비 기대값 서비스 - Facade (P1-5: God Class 분해)
 *
 * <h3>책임: 비동기 오케스트레이션</h3>
 *
 * <ul>
 *   <li>비동기 dispatch (calculateExpectationAsync, getGzipExpectationAsync)
 *   <li>캐릭터 조회 → 장비 로드 → 프리셋 계산 → 응답 빌드
 * </ul>
 *
 * <h3>위임된 책임</h3>
 *
 * <ul>
 *   <li>프리셋 계산: {@link PresetCalculationHelper}
 *   <li>캐시 관리: {@link ExpectationCacheCoordinator}
 *   <li>영속성: {@link ExpectationPersistenceService}
 * </ul>
 */
@Slf4j
@Service
public class EquipmentExpectationServiceV4 {

  private static final long ASYNC_TIMEOUT_SECONDS = 30L;
  private static final long DATA_LOAD_TIMEOUT_SECONDS = 10L;

  private final GameCharacterFacade gameCharacterFacade;
  private final GameCharacterService gameCharacterService;
  private final EquipmentDataProvider equipmentProvider;
  private final EquipmentStreamingParser streamingParser;
  private final PresetCalculationHelper presetHelper;
  private final StarforceLookupTable starforceLookupTable;
  private final LogicExecutor executor;
  private final Executor equipmentExecutor;
  private final Executor presetExecutor;
  private final ExpectationCacheCoordinator cacheCoordinator;
  private final ExpectationPersistenceService persistenceService;
  private final ObjectProvider<EquipmentExpectationServiceV4> selfProvider;

  public EquipmentExpectationServiceV4(
      GameCharacterFacade gameCharacterFacade,
      GameCharacterService gameCharacterService,
      EquipmentDataProvider equipmentProvider,
      EquipmentStreamingParser streamingParser,
      PresetCalculationHelper presetHelper,
      StarforceLookupTable starforceLookupTable,
      LogicExecutor executor,
      @Qualifier("equipmentProcessingExecutor") Executor equipmentExecutor,
      @Qualifier("presetCalculationExecutor") Executor presetExecutor,
      ExpectationCacheCoordinator cacheCoordinator,
      ExpectationPersistenceService persistenceService,
      ObjectProvider<EquipmentExpectationServiceV4> selfProvider) {
    this.gameCharacterFacade = gameCharacterFacade;
    this.gameCharacterService = gameCharacterService;
    this.equipmentProvider = equipmentProvider;
    this.streamingParser = streamingParser;
    this.presetHelper = presetHelper;
    this.starforceLookupTable = starforceLookupTable;
    this.executor = executor;
    this.equipmentExecutor = equipmentExecutor;
    this.presetExecutor = presetExecutor;
    this.cacheCoordinator = cacheCoordinator;
    this.persistenceService = persistenceService;
    this.selfProvider = selfProvider;
  }

  // ==================== Public API ====================

  /** 캐릭터 기대값 계산 (비동기) */
  @TraceLog
  public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(
      String userIgn) {
    return calculateExpectationAsync(userIgn, false);
  }

  /** 캐릭터 기대값 계산 (비동기, force 옵션) */
  @TraceLog
  public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(
      String userIgn, boolean force) {
    return CompletableFuture.supplyAsync(
            () -> selfProvider.getObject().calculateExpectation(userIgn, force), equipmentExecutor)
        .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** GZIP 압축된 기대값 응답 반환 (비동기) (#262 성능 최적화) */
  @TraceLog
  public CompletableFuture<byte[]> getGzipExpectationAsync(String userIgn, boolean force) {
    return CompletableFuture.supplyAsync(
            () -> getGzipExpectation(userIgn, force), equipmentExecutor)
        .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** 캐릭터 기대값 계산 (동기, force 옵션) */
  @Transactional
  public EquipmentExpectationResponseV4 calculateExpectation(String userIgn, boolean force) {
    validateInitialized();
    return cacheCoordinator.getOrCalculate(userIgn, force, () -> doCalculateExpectation(userIgn));
  }

  /** GZIP 압축된 기대값 응답 반환 (동기) */
  public byte[] getGzipExpectation(String userIgn, boolean force) {
    validateInitialized();
    return cacheCoordinator.getGzipOrCalculate(
        userIgn, force, () -> doCalculateExpectation(userIgn));
  }

  /** L1 캐시 직접 조회 - Fast Path (#264 성능 최적화) */
  public Optional<byte[]> getGzipFromL1CacheDirect(String userIgn) {
    return cacheCoordinator.getGzipFromL1CacheDirect(userIgn);
  }

  // ==================== Internal Calculation ====================

  private void validateInitialized() {
    if (!starforceLookupTable.isInitialized()) {
      throw new StarforceNotInitializedException();
    }
  }

  /**
   * 실제 기대값 계산 로직 (Singleflight Leader가 실행)
   *
   * <h3>P0-2: 비동기 체이닝 적용</h3>
   *
   * <p>loadEquipmentDataAsync로 API 대기 시간 분리. TieredCache Callable 내에서는 .join() 사용 (분산 락 내부이므로 스레드
   * 점유 제한적)
   */
  private EquipmentExpectationResponseV4 doCalculateExpectation(String userIgn) {
    TaskContext context = TaskContext.of("ExpectationV4", "Calculate", userIgn);

    return executor.execute(
        () -> {
          GameCharacter character = findCharacterBypassingWorker(userIgn);
          byte[] equipmentData =
              loadEquipmentDataAsync(character).join(); // TieredCache Callable 내부 → 동기 필요
          List<PresetExpectation> presetResults =
              calculateAllPresets(equipmentData, character.getCharacterClass());
          PresetExpectation maxPreset = findMaxPreset(presetResults);
          persistenceService.saveResults(character.getId(), presetResults);
          return buildResponse(userIgn, maxPreset, presetResults, false);
        },
        context);
  }

  /**
   * V2 워커 풀을 우회하고 직접 캐릭터를 조회/생성 (V5 CQRS 전용)
   *
   * <p>V5 워커가 V2 워커 풀에 의존하지 않도록 V2 Service를 직접 호출
   */
  private GameCharacter findCharacterBypassingWorker(String userIgn) {
    return executor.execute(
        () -> {
          // 1. 캐시된 캐릭터 조회
          Optional<GameCharacter> cached = gameCharacterService.getCharacterIfExist(userIgn);
          if (cached.isPresent()) {
            return cached.get();
          }

          // 2. Negative Cache 확인
          if (gameCharacterService.isNonExistent(userIgn)) {
            throw new maple.expectation.error.exception.CharacterNotFoundException(userIgn);
          }

          // 3. 직접 생성 (V2 워커 풀 우회)
          return gameCharacterService.createNewCharacter(userIgn);
        },
        TaskContext.of("V4", "FindCharacterBypassingWorker", userIgn));
  }

  private PresetExpectation findMaxPreset(List<PresetExpectation> presetResults) {
    return presetResults.stream()
        .max((p1, p2) -> p1.getTotalExpectedCost().compareTo(p2.getTotalExpectedCost()))
        .orElse(null);
  }

  private EquipmentExpectationResponseV4 buildResponse(
      String userIgn,
      PresetExpectation maxPreset,
      List<PresetExpectation> presetResults,
      boolean fromCache) {
    BigDecimal totalCost = maxPreset != null ? maxPreset.getTotalExpectedCost() : BigDecimal.ZERO;
    CostBreakdownDto totalBreakdown =
        maxPreset != null ? maxPreset.getCostBreakdown() : CostBreakdownDto.empty();
    int maxPresetNo = maxPreset != null ? maxPreset.getPresetNo() : 0;

    return EquipmentExpectationResponseV4.builder()
        .userIgn(userIgn)
        .calculatedAt(LocalDateTime.now())
        .fromCache(fromCache)
        .totalExpectedCost(totalCost)
        .totalCostText(CostFormatter.format(totalCost))
        .totalCostBreakdown(totalBreakdown)
        .maxPresetNo(maxPresetNo)
        .presets(presetResults)
        .build();
  }

  // ==================== Equipment Loading ====================

  /**
   * 장비 데이터 비동기 로드 (P0-2: .join() 블로킹 분리)
   *
   * <p>DB에 캐시된 데이터가 있으면 즉시 반환, 없으면 API 비동기 호출
   */
  private CompletableFuture<byte[]> loadEquipmentDataAsync(GameCharacter character) {
    if (character.getEquipment() != null && character.getEquipment().getJsonContent() != null) {
      return CompletableFuture.completedFuture(
          character.getEquipment().getJsonContent().getBytes());
    }
    return equipmentProvider
        .getRawEquipmentData(character.getOcid())
        .orTimeout(DATA_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  // ==================== Preset Calculation ====================

  private List<PresetExpectation> calculateAllPresets(byte[] equipmentData, String characterClass) {
    byte[] decompressedData = streamingParser.decompressIfNeeded(equipmentData);

    List<CompletableFuture<PresetExpectation>> futures =
        IntStream.rangeClosed(1, 3)
            .mapToObj(
                presetNo ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          var cubeInputs =
                              streamingParser.parseCubeInputsForPreset(decompressedData, presetNo);
                          return presetHelper.calculatePreset(cubeInputs, presetNo, characterClass);
                        },
                        presetExecutor))
            .toList();

    return futures.stream()
        .map(this::joinPresetFuture)
        .filter(preset -> !preset.getItems().isEmpty())
        .toList();
  }

  private PresetExpectation joinPresetFuture(CompletableFuture<PresetExpectation> future) {
    return executor.execute(
        () -> future.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        TaskContext.of("V4", "PresetJoin"));
  }
}
