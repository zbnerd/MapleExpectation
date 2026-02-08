package maple.expectation.service.v2;

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.aop.context.SkipEquipmentL2CacheContext;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.concurrency.SingleFlightExecutor;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.global.error.exception.ExpectationCalculationUnavailableException;
import maple.expectation.global.error.exception.TransactionSnapshotException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.util.StringMaskingUtils;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.service.v2.cache.EquipmentCacheService;
import maple.expectation.service.v2.cache.EquipmentDataResolver;
import maple.expectation.service.v2.cache.EquipmentFingerprintGenerator;
import maple.expectation.service.v2.cache.TotalExpectationCacheService;
import maple.expectation.service.v2.calculator.ExpectationCalculator;
import maple.expectation.service.v2.calculator.ExpectationCalculatorFactory;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import maple.expectation.service.v2.mapper.EquipmentMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 장비 기대값 계산 서비스 (오케스트레이션)
 *
 * <h4>Issue #158 + #118 핵심 변경사항</h4>
 *
 * <ul>
 *   <li>TotalExpectationResponse 결과 캐싱 (L1+L2)
 *   <li>Cache HIT 시 장비 로드/파싱/계산 완전 스킵
 *   <li>Single-flight 패턴으로 동시 MISS 중복 계산 방지
 *   <li><b>#118 준수: 비동기 파이프라인 전환 (.join() 완전 제거)</b>
 * </ul>
 *
 * <h4>SRP 리팩토링 (v2.5)</h4>
 *
 * <ul>
 *   <li>SingleFlightExecutor: 동시성 제어 위임
 *   <li>EquipmentDataResolver: 데이터 소스 우선순위 처리 위임
 *   <li>본 서비스: 순수 오케스트레이션만 담당
 * </ul>
 */
@Slf4j
@Service
public class EquipmentService {

  // ==================== 상수 ====================

  /** Leader compute 데드라인 (초) */
  private static final int LEADER_DEADLINE_SECONDS = 30;

  /**
   * Follower 대기 타임아웃 (초)
   *
   * <p>Leader 데드라인과 동일하게 설정 (Issue #158 부하테스트 에러 수정)
   *
   * <p>5초 → 30초: Follower가 Leader 완료 전 timeout되어 S006 에러 폭발 방지
   */
  private static final int FOLLOWER_TIMEOUT_SECONDS = LEADER_DEADLINE_SECONDS;

  // ==================== 의존성 ====================

  private final GameCharacterFacade gameCharacterFacade;
  private final EquipmentDataProvider equipmentProvider;
  private final EquipmentStreamingParser streamingParser;
  private final ExpectationCalculatorFactory calculatorFactory;
  private final EquipmentMapper equipmentMapper;
  private final EquipmentCacheService equipmentCacheService;
  private final TotalExpectationCacheService expectationCacheService;
  private final EquipmentFingerprintGenerator fingerprintGenerator;
  private final EquipmentDataResolver dataResolver;
  private final LogicExecutor executor;
  private final TransactionTemplate readOnlyTx;
  private final Executor expectationComputeExecutor;
  private final maple.expectation.config.CalculationProperties calculationProperties;

  /** Single-flight 동시성 제어 */
  private final SingleFlightExecutor<TotalExpectationResponse> singleFlightExecutor;

  // ==================== 생성자 ====================

  public EquipmentService(
      GameCharacterFacade gameCharacterFacade,
      EquipmentDataProvider equipmentProvider,
      EquipmentStreamingParser streamingParser,
      ExpectationCalculatorFactory calculatorFactory,
      EquipmentMapper equipmentMapper,
      EquipmentCacheService equipmentCacheService,
      TotalExpectationCacheService expectationCacheService,
      EquipmentFingerprintGenerator fingerprintGenerator,
      EquipmentDataResolver dataResolver,
      LogicExecutor executor,
      @Qualifier("readOnlyTransactionTemplate") TransactionTemplate readOnlyTx,
      @Qualifier("expectationComputeExecutor") Executor expectationComputeExecutor,
      maple.expectation.config.CalculationProperties calculationProperties,
      maple.expectation.config.SingleFlightExecutorFactory singleFlightFactory) {

    this.gameCharacterFacade = gameCharacterFacade;
    this.equipmentProvider = equipmentProvider;
    this.streamingParser = streamingParser;
    this.calculatorFactory = calculatorFactory;
    this.equipmentMapper = equipmentMapper;
    this.equipmentCacheService = equipmentCacheService;
    this.expectationCacheService = expectationCacheService;
    this.fingerprintGenerator = fingerprintGenerator;
    this.dataResolver = dataResolver;
    this.executor = executor;
    this.readOnlyTx = readOnlyTx;
    this.expectationComputeExecutor = expectationComputeExecutor;
    this.calculationProperties = calculationProperties;

    // SingleFlightExecutor 초기화 (DIP: factory 사용)
    this.singleFlightExecutor =
        singleFlightFactory.create(
            FOLLOWER_TIMEOUT_SECONDS, expectationComputeExecutor, this::fallbackFromCache);
  }

  // ==================== 내부 Record (Tx Snapshot) ====================

  /**
   * 캐릭터 스냅샷: 캐시 키 생성 + 계산용 (단일 조회)
   *
   * <h4>P0-2 리팩토링: Two-Phase → Single-Phase</h4>
   *
   * <p>기존 LightSnapshot/FullSnapshot 분리 구조에서 동일 필드 구조임에도 DB를 2회 조회하던 문제를 해결. 단일 스냅샷으로 통합하여 DB 부하
   * 50% 감소.
   *
   * <h4>데이터 일관성 보장</h4>
   *
   * <p>equipmentUpdatedAt 변경 감지는 EquipmentDataResolver의 DB TTL(15분)로 보장되므로 2회 조회 기반의
   * validateAndResolveCacheKey() 패턴이 불필요.
   */
  private record CharacterSnapshot(String userIgn, String ocid, LocalDateTime equipmentUpdatedAt) {}

  // ==================== 메인 API (비동기) ====================

  /**
   * 기대값 계산 - 비동기 버전 (Issue #118 준수)
   *
   * <h4>P0-2 리팩토링: Single-Phase Snapshot</h4>
   *
   * <p>기존 Two-Phase(Light→Full) 패턴에서 동일 필드 구조임에도 DB를 2회 조회하던 문제를 단일 CharacterSnapshot으로 통합하여 DB 부하
   * 50% 감소.
   */
  @TraceLog
  public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationAsync(
      String userIgn) {
    String beforeContext = SkipEquipmentL2CacheContext.snapshot();

    return CompletableFuture.supplyAsync(
            () -> {
              SkipEquipmentL2CacheContext.restore("true"); // V5: MDC 기반
              return fetchCharacterSnapshot(userIgn);
            },
            expectationComputeExecutor)
        .thenCompose(this::processAfterSnapshot)
        .orTimeout(LEADER_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .exceptionally(e -> handleAsyncException(e, userIgn))
        .whenComplete((r, e) -> SkipEquipmentL2CacheContext.restore(beforeContext));
  }

  // ==================== 오케스트레이션 ====================

  /**
   * 스냅샷 기반 오케스트레이션 (P0-2: Single-Phase)
   *
   * <p>단일 DB 조회로 캐시 키 생성 + 계산을 모두 처리합니다.
   */
  private CompletableFuture<TotalExpectationResponse> processAfterSnapshot(
      CharacterSnapshot snapshot) {
    String cacheKey = buildExpectationCacheKey(snapshot);

    // Early Return: 캐시 HIT
    Optional<TotalExpectationResponse> cached = expectationCacheService.getValidCache(cacheKey);
    if (cached.isPresent()) {
      log.debug("[Expectation] Cache HIT for {}", StringMaskingUtils.maskOcid(snapshot.ocid()));
      return CompletableFuture.completedFuture(cached.get());
    }

    // MISS: Single-flight 위임 (추가 DB 조회 없이 바로 계산)
    return singleFlightExecutor.executeAsync(
        cacheKey, () -> computeAndCacheAsync(snapshot, cacheKey));
  }

  // ==================== 스냅샷 조회 ====================

  /**
   * 캐릭터 스냅샷 단일 조회 (P0-2: DB 부하 50% 감소)
   *
   * <p>기존 fetchLightSnapshot/fetchFullSnapshot 2회 조회를 1회로 통합.
   */
  private CharacterSnapshot fetchCharacterSnapshot(String userIgn) {
    CharacterSnapshot snap =
        readOnlyTx.execute(
            status -> {
              GameCharacter ch = gameCharacterFacade.findCharacterByUserIgn(userIgn);

              // Issue #120: Rich Domain - 비활성 캐릭터 감지 (30일 이상 미갱신)
              if (!ch.isActive()) {
                log.debug("[Expectation] 비활성 캐릭터 감지: userIgn={}", userIgn);
              }

              return new CharacterSnapshot(
                  ch.getUserIgn(),
                  ch.getOcid(),
                  ch.getEquipment() != null ? ch.getEquipment().getUpdatedAt() : null);
            });
    if (snap == null) {
      throw new TransactionSnapshotException(userIgn);
    }
    return snap;
  }

  // ==================== 캐시 키 ====================

  private String buildExpectationCacheKey(CharacterSnapshot snapshot) {
    String fingerprint = fingerprintGenerator.generate(snapshot.equipmentUpdatedAt());
    String tableVersionHash =
        fingerprintGenerator.hashTableVersion(calculationProperties.getTableVersion());
    return expectationCacheService.buildCacheKey(
        snapshot.ocid(), fingerprint, tableVersionHash, calculationProperties.getLogicVersion());
  }

  // ==================== 계산 로직 ====================

  /**
   * 계산 및 캐싱 (P1-3: thenApplyAsync → thenApply)
   *
   * <p>dataResolver.resolveAsync()가 이미 비동기로 실행되므로 thenApply()로 동일 스레드에서 후속 처리하여 불필요한 컨텍스트 스위칭을
   * 제거합니다.
   */
  private CompletableFuture<TotalExpectationResponse> computeAndCacheAsync(
      CharacterSnapshot snapshot, String cacheKey) {

    // EquipmentDataResolver에 위임 (DB 조회 + API 호출 + DB 저장 모두 내부 처리)
    return dataResolver
        .resolveAsync(snapshot.ocid(), snapshot.userIgn())
        .thenApply(
            targetData -> {
              List<CubeCalculationInput> inputs = streamingParser.parseCubeInputs(targetData);
              TotalExpectationResponse result = processCalculation(snapshot.userIgn(), inputs);
              expectationCacheService.saveCache(cacheKey, result);
              return result;
            });
  }

  private TotalExpectationResponse processCalculation(
      String userIgn, List<CubeCalculationInput> inputs) {
    return executor.execute(
        () -> {
          List<TotalExpectationResponse.ItemExpectation> details =
              inputs.stream().map(this::mapToItemExpectation).toList();

          long totalCost =
              details.stream()
                  .mapToLong(TotalExpectationResponse.ItemExpectation::getExpectedCost)
                  .sum();

          return equipmentMapper.toTotalResponse(userIgn, totalCost, details);
        },
        TaskContext.of("EquipmentService", "ProcessCalculation", userIgn));
  }

  private TotalExpectationResponse.ItemExpectation mapToItemExpectation(
      CubeCalculationInput input) {
    ExpectationCalculator calc = calculatorFactory.createBlackCubeCalculator(input);
    return equipmentMapper.toItemExpectation(
        input, calc.calculateCost(), calc.getTrials().orElse(0L));
  }

  // ==================== 예외 처리 ====================

  private TotalExpectationResponse handleAsyncException(Throwable e, String userIgn) {
    Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;

    if (cause instanceof TimeoutException) {
      throw new ExpectationCalculationUnavailableException(userIgn, cause);
    }
    if (cause instanceof RuntimeException re) {
      throw re;
    }
    throw new EquipmentDataProcessingException(
        String.format("Async expectation calculation failed for: %s", userIgn), cause);
  }

  private TotalExpectationResponse fallbackFromCache(String cacheKey) {
    log.warn("[Expectation] Follower timeout, fallback to cache lookup");
    return expectationCacheService
        .getValidCache(cacheKey)
        .orElseThrow(
            () ->
                new ExpectationCalculationUnavailableException(
                    StringMaskingUtils.maskCacheKey(cacheKey)));
  }

  // ==================== 레거시 API (Issue #118: 비동기 전환) ====================

  /**
   * 기대값 계산 (Legacy) - 비동기 버전 (Issue #118 준수)
   *
   * <p>DB 저장 없이 Nexon API 직접 호출하여 계산
   *
   * <p>신규 코드는 {@link #calculateTotalExpectationAsync(String)} 사용 권장
   *
   * @param userIgn 캐릭터 닉네임
   * @return 기대값 계산 결과 Future
   */
  @TraceLog
  public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationLegacyAsync(
      String userIgn) {
    return CompletableFuture.supplyAsync(() -> getOcid(userIgn), expectationComputeExecutor)
        .thenCompose(ocid -> equipmentProvider.getEquipmentResponse(ocid))
        .thenApplyAsync(
            equipment -> processLegacyCalculation(userIgn, equipment), expectationComputeExecutor)
        .orTimeout(LEADER_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .exceptionally(e -> handleAsyncException(e, userIgn));
  }

  /** Legacy 계산 로직 (Method Extraction - CLAUDE.md Section 15) */
  private TotalExpectationResponse processLegacyCalculation(
      String userIgn, EquipmentResponse equipment) {
    return executor.execute(
        () -> {
          List<CubeCalculationInput> inputs =
              equipment.getItemEquipment().stream()
                  .filter(item -> item.getPotentialOptionGrade() != null)
                  .map(equipmentMapper::toCubeInput)
                  .toList();
          return processCalculation(userIgn, inputs);
        },
        TaskContext.of("EquipmentService", "ProcessLegacy", userIgn));
  }

  /**
   * 장비 데이터 Zero-Copy 스트리밍 (Issue #63)
   *
   * <p>GZIP 압축된 데이터를 그대로 전송합니다.
   *
   * @param userIgn 캐릭터 닉네임
   * @param outputStream 출력 스트림 (Content-Encoding: gzip 필요)
   */
  public void streamEquipmentDataRaw(String userIgn, OutputStream outputStream) {
    executor.executeVoid(
        () -> equipmentProvider.streamRaw(getOcid(userIgn), outputStream),
        TaskContext.of("EquipmentService", "StreamDataRaw", userIgn));
  }

  /**
   * 장비 조회 - 비동기 버전 (Issue #118 준수)
   *
   * <p>톰캣 스레드를 즉시 반환하고 expectation-* 스레드에서 처리
   *
   * @param userIgn 캐릭터 닉네임
   * @return 장비 응답 Future
   */
  @TraceLog
  public CompletableFuture<EquipmentResponse> getEquipmentByUserIgnAsync(String userIgn) {
    return CompletableFuture.supplyAsync(() -> getOcid(userIgn), expectationComputeExecutor)
        .thenCompose(ocid -> equipmentProvider.getEquipmentResponse(ocid))
        .orTimeout(LEADER_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .exceptionally(e -> handleEquipmentException(e, userIgn));
  }

  // ==================== 예외 처리 (Issue #118) ====================

  private EquipmentResponse handleEquipmentException(Throwable e, String userIgn) {
    Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;

    if (cause instanceof TimeoutException) {
      throw new ExpectationCalculationUnavailableException(userIgn, cause);
    }
    if (cause instanceof RuntimeException re) {
      throw re;
    }
    throw new EquipmentDataProcessingException(
        "Async equipment fetch failed for: " + userIgn, cause);
  }

  // ==================== 유틸리티 ====================

  private String getOcid(String userIgn) {
    return gameCharacterFacade.findCharacterByUserIgn(userIgn).getOcid();
  }
}
