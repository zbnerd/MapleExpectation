package maple.expectation.service.v2;

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
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.parser.EquipmentStreamingParser;
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

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 장비 기대값 계산 서비스 (오케스트레이션)
 *
 * <h4>Issue #158 + #118 핵심 변경사항</h4>
 * <ul>
 *   <li>TotalExpectationResponse 결과 캐싱 (L1+L2)</li>
 *   <li>Cache HIT 시 장비 로드/파싱/계산 완전 스킵</li>
 *   <li>Single-flight 패턴으로 동시 MISS 중복 계산 방지</li>
 *   <li><b>#118 준수: 비동기 파이프라인 전환 (.join() 완전 제거)</b></li>
 * </ul>
 *
 * <h4>SRP 리팩토링 (v2.5)</h4>
 * <ul>
 *   <li>SingleFlightExecutor: 동시성 제어 위임</li>
 *   <li>EquipmentDataResolver: 데이터 소스 우선순위 처리 위임</li>
 *   <li>본 서비스: 순수 오케스트레이션만 담당</li>
 * </ul>
 */
@Slf4j
@Service
public class EquipmentService {

    // ==================== 상수 ====================

    /** 계산 로직 버전 (캐시 키에 포함) */
    private static final int LOGIC_VERSION = 3;

    /** 테이블 버전 (cube_tables 변경 시 갱신) */
    private static final String TABLE_VERSION = "2024.01.15";

    /** Leader compute 데드라인 (초) */
    private static final int LEADER_DEADLINE_SECONDS = 30;

    /**
     * Follower 대기 타임아웃 (초)
     *
     * <p>Leader 데드라인과 동일하게 설정 (Issue #158 부하테스트 에러 수정)</p>
     * <p>5초 → 30초: Follower가 Leader 완료 전 timeout되어 S006 에러 폭발 방지</p>
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
            @Qualifier("expectationComputeExecutor") Executor expectationComputeExecutor) {

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

        // SingleFlightExecutor 초기화 (타임아웃 시 캐시 재조회 fallback)
        this.singleFlightExecutor = new SingleFlightExecutor<>(
                FOLLOWER_TIMEOUT_SECONDS,
                expectationComputeExecutor,
                this::fallbackFromCache
        );
    }

    // ==================== 내부 Record (Tx Snapshot) ====================

    /**
     * 1차 경량 스냅샷: 캐시 키 생성용
     */
    private record LightSnapshot(
            String userIgn,
            String ocid,
            LocalDateTime equipmentUpdatedAt
    ) {}

    /**
     * 2차 전체 스냅샷: 계산용 (MISS일 때만 사용)
     *
     * <p>Issue #158 리팩토링: equipmentJson 제거</p>
     * <p>DB 조회는 EquipmentDataResolver → EquipmentDbWorker로 위임 (SRP)</p>
     */
    private record FullSnapshot(
            String userIgn,
            String ocid,
            LocalDateTime equipmentUpdatedAt
    ) {}

    // ==================== 메인 API (비동기) ====================

    /**
     * 기대값 계산 - 비동기 버전 (Issue #118 준수)
     */
    @TraceLog
    public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationAsync(String userIgn) {
        String beforeContext = SkipEquipmentL2CacheContext.snapshot();

        return CompletableFuture
                .supplyAsync(() -> {
                    SkipEquipmentL2CacheContext.restore("true"); // V5: MDC 기반
                    return fetchLightSnapshot(userIgn);
                }, expectationComputeExecutor)
                .thenCompose(light -> processAfterLightSnapshot(userIgn, light))
                .orTimeout(LEADER_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> handleAsyncException(e, userIgn))
                .whenComplete((r, e) -> SkipEquipmentL2CacheContext.restore(beforeContext));
    }

    // ==================== 오케스트레이션 ====================

    private CompletableFuture<TotalExpectationResponse> processAfterLightSnapshot(
            String userIgn, LightSnapshot light) {

        String cacheKey = buildExpectationCacheKey(light);

        // Early Return: 캐시 HIT
        Optional<TotalExpectationResponse> cached = expectationCacheService.getValidCache(cacheKey);
        if (cached.isPresent()) {
            log.debug("[Expectation] Cache HIT for {}", maskOcid(light.ocid()));
            return CompletableFuture.completedFuture(cached.get());
        }

        // MISS: FullSnapshot 로드 → 계산
        return CompletableFuture
                .supplyAsync(() -> fetchFullSnapshot(userIgn), expectationComputeExecutor)
                .thenCompose(full -> processAfterFullSnapshot(light, full, cacheKey));
    }

    private CompletableFuture<TotalExpectationResponse> processAfterFullSnapshot(
            LightSnapshot light, FullSnapshot full, String originalCacheKey) {

        String finalCacheKey = validateAndResolveCacheKey(light, full, originalCacheKey);

        // 캐시 키 변경 시 재조회
        if (!finalCacheKey.equals(originalCacheKey)) {
            Optional<TotalExpectationResponse> reCached = expectationCacheService.getValidCache(finalCacheKey);
            if (reCached.isPresent()) {
                log.debug("[Expectation] Cache HIT after key regeneration");
                return CompletableFuture.completedFuture(reCached.get());
            }
        }

        // Single-flight 위임
        return singleFlightExecutor.executeAsync(
                finalCacheKey,
                () -> computeAndCacheAsync(full, finalCacheKey)
        );
    }

    // ==================== 스냅샷 조회 ====================

    private LightSnapshot fetchLightSnapshot(String userIgn) {
        LightSnapshot snap = readOnlyTx.execute(status -> {
            GameCharacter ch = gameCharacterFacade.findCharacterByUserIgn(userIgn);
            return new LightSnapshot(
                    ch.getUserIgn(),
                    ch.getOcid(),
                    ch.getEquipment() != null ? ch.getEquipment().getUpdatedAt() : null
            );
        });
        if (snap == null) {
            throw new IllegalStateException("TransactionTemplate returned null for: " + userIgn);
        }
        return snap;
    }

    private FullSnapshot fetchFullSnapshot(String userIgn) {
        FullSnapshot snap = readOnlyTx.execute(status -> {
            GameCharacter ch = gameCharacterFacade.findCharacterByUserIgn(userIgn);
            return new FullSnapshot(
                    ch.getUserIgn(),
                    ch.getOcid(),
                    ch.getEquipment() != null ? ch.getEquipment().getUpdatedAt() : null
            );
        });
        if (snap == null) {
            throw new IllegalStateException("TransactionTemplate returned null for: " + userIgn);
        }
        return snap;
    }

    // ==================== 캐시 키 ====================

    private String buildExpectationCacheKey(LightSnapshot light) {
        String fingerprint = fingerprintGenerator.generate(light.equipmentUpdatedAt());
        String tableVersionHash = fingerprintGenerator.hashTableVersion(TABLE_VERSION);
        return expectationCacheService.buildCacheKey(
                light.ocid(), fingerprint, tableVersionHash, LOGIC_VERSION);
    }

    private String validateAndResolveCacheKey(LightSnapshot light, FullSnapshot full, String originalCacheKey) {
        if (Objects.equals(light.equipmentUpdatedAt(), full.equipmentUpdatedAt())) {
            return originalCacheKey;
        }

        log.info("[Expectation] updatedAt mismatch, regenerating cacheKey");
        String fingerprint = fingerprintGenerator.generate(full.equipmentUpdatedAt());
        String tableVersionHash = fingerprintGenerator.hashTableVersion(TABLE_VERSION);
        return expectationCacheService.buildCacheKey(full.ocid(), fingerprint, tableVersionHash, LOGIC_VERSION);
    }

    // ==================== 계산 로직 ====================

    private CompletableFuture<TotalExpectationResponse> computeAndCacheAsync(
            FullSnapshot snap, String cacheKey) {

        // EquipmentDataResolver에 위임 (DB 조회 + API 호출 + DB 저장 모두 내부 처리)
        return dataResolver.resolveAsync(snap.ocid(), snap.userIgn())
                .thenApplyAsync(targetData -> {
                    List<CubeCalculationInput> inputs = streamingParser.parseCubeInputs(targetData);
                    TotalExpectationResponse result = processCalculation(snap.userIgn(), inputs);
                    expectationCacheService.saveCache(cacheKey, result);
                    return result;
                }, expectationComputeExecutor);
    }

    private TotalExpectationResponse processCalculation(String userIgn, List<CubeCalculationInput> inputs) {
        return executor.execute(() -> {
            List<TotalExpectationResponse.ItemExpectation> details = inputs.stream()
                    .map(this::mapToItemExpectation)
                    .toList();

            long totalCost = details.stream()
                    .mapToLong(TotalExpectationResponse.ItemExpectation::getExpectedCost)
                    .sum();

            return equipmentMapper.toTotalResponse(userIgn, totalCost, details);
        }, TaskContext.of("EquipmentService", "ProcessCalculation", userIgn));
    }

    private TotalExpectationResponse.ItemExpectation mapToItemExpectation(CubeCalculationInput input) {
        ExpectationCalculator calc = calculatorFactory.createBlackCubeCalculator(input);
        return equipmentMapper.toItemExpectation(
                input,
                calc.calculateCost(),
                calc.getTrials().orElse(0L)
        );
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
        throw new RuntimeException("Async expectation calculation failed", cause);
    }

    private TotalExpectationResponse fallbackFromCache(String cacheKey) {
        log.warn("[Expectation] Follower timeout, fallback to cache lookup");
        return expectationCacheService.getValidCache(cacheKey)
                .orElseThrow(() -> new ExpectationCalculationUnavailableException(maskKey(cacheKey)));
    }

    // ==================== 유틸리티 ====================

    private String maskOcid(String value) {
        if (value == null || value.length() < 8) return "***";
        return value.substring(0, 4) + "***";
    }

    private String maskKey(String key) {
        if (key == null) return "null";
        return key.replaceAll("(expectation:v\\d+:)[^:]+", "$1***");
    }

    // ==================== 레거시 API (Issue #118: 비동기 전환) ====================

    /**
     * 기대값 계산 (Legacy) - 비동기 버전 (Issue #118 준수)
     *
     * <p>DB 저장 없이 Nexon API 직접 호출하여 계산</p>
     * <p>신규 코드는 {@link #calculateTotalExpectationAsync(String)} 사용 권장</p>
     *
     * @param userIgn 캐릭터 닉네임
     * @return 기대값 계산 결과 Future
     */
    @TraceLog
    public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationLegacyAsync(String userIgn) {
        return CompletableFuture
                .supplyAsync(() -> getOcid(userIgn), expectationComputeExecutor)
                .thenCompose(ocid -> equipmentProvider.getEquipmentResponse(ocid))
                .thenApplyAsync(equipment -> processLegacyCalculation(userIgn, equipment), expectationComputeExecutor)
                .orTimeout(LEADER_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> handleAsyncException(e, userIgn));
    }

    /**
     * Legacy 계산 로직 (Method Extraction - CLAUDE.md Section 15)
     */
    private TotalExpectationResponse processLegacyCalculation(String userIgn, EquipmentResponse equipment) {
        return executor.execute(() -> {
            List<CubeCalculationInput> inputs = equipment.getItemEquipment().stream()
                    .filter(item -> item.getPotentialOptionGrade() != null)
                    .map(equipmentMapper::toCubeInput)
                    .toList();
            return processCalculation(userIgn, inputs);
        }, TaskContext.of("EquipmentService", "ProcessLegacy", userIgn));
    }

    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        executor.executeVoid(
                () -> equipmentProvider.streamAndDecompress(getOcid(userIgn), outputStream),
                TaskContext.of("EquipmentService", "StreamData", userIgn)
        );
    }

    /**
     * 장비 조회 - 비동기 버전 (Issue #118 준수)
     *
     * <p>톰캣 스레드를 즉시 반환하고 expectation-* 스레드에서 처리</p>
     *
     * @param userIgn 캐릭터 닉네임
     * @return 장비 응답 Future
     */
    @TraceLog
    public CompletableFuture<EquipmentResponse> getEquipmentByUserIgnAsync(String userIgn) {
        return CompletableFuture
                .supplyAsync(() -> getOcid(userIgn), expectationComputeExecutor)
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
        throw new EquipmentDataProcessingException("Async equipment fetch failed for: " + userIgn, cause);
    }

    // ==================== 유틸리티 ====================

    private String getOcid(String userIgn) {
        return gameCharacterFacade.findCharacterByUserIgn(userIgn).getOcid();
    }
}
