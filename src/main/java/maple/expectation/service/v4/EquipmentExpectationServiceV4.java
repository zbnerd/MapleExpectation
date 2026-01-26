package maple.expectation.service.v4;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.cost.CostFormatter;
import maple.expectation.domain.equipment.SecondaryWeaponCategory;
import maple.expectation.domain.v2.EquipmentExpectationSummary;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.v4.EquipmentCalculationInput;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CubeExpectationDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.ItemExpectationV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.StarforceExpectationDto;
import maple.expectation.service.v2.starforce.config.NoljangProbabilityTable;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.repository.v2.EquipmentExpectationSummaryRepository;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculatorFactory;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import maple.expectation.service.v2.starforce.StarforceLookupTable;
import maple.expectation.util.GzipUtils;
import maple.expectation.global.cache.TieredCacheManager;
import maple.expectation.service.v4.buffer.ExpectationWriteBackBuffer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.stream.IntStream;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * V4 장비 기대값 서비스 (#240, #266 ADR 정합성 리팩토링)
 *
 * <h3>5-Agent Council 합의사항</h3>
 * <ul>
 *   <li>🔴 Red (SRE): 전용 Executor 사용 (equipmentProcessingExecutor)</li>
 *   <li>🟣 Purple (Auditor): BigDecimal 정밀 계산</li>
 *   <li>🟢 Green (Performance): DB 저장으로 Buffer Pool 오염 방지, 병렬 프리셋 계산</li>
 * </ul>
 *
 * <h3>P1-2: 병렬 프리셋 계산 (#266)</h3>
 * <ul>
 *   <li>프리셋 1, 2, 3 동시 계산 (CompletableFuture)</li>
 *   <li>300ms → 110ms 성능 개선 (3x)</li>
 *   <li>presetCalculationExecutor로 Deadlock 방지</li>
 * </ul>
 *
 * <h3>P1-3: Write-Behind 버퍼 적용 (#266)</h3>
 * <ul>
 *   <li>DB 저장을 메모리 버퍼로 위임</li>
 *   <li>15-30ms → 0.1ms 성능 개선 (150-300x)</li>
 *   <li>백프레셔 발생 시 동기 폴백</li>
 * </ul>
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>프리셋별 기대값 계산 (프리셋 1, 2, 3)</li>
 *   <li>비용 상세 분류 (블랙큐브, 레드큐브, 에디셔널, 스타포스)</li>
 *   <li>계산 결과 DB 저장 (EquipmentExpectationSummary)</li>
 * </ul>
 */
@Slf4j
@Service
public class EquipmentExpectationServiceV4 {

    // #240 V4: DEFAULT_TARGET_STAR 제거 - JSON starforce 필드 값 사용
    private static final String CACHE_NAME = "expectationV4";

    private final GameCharacterFacade gameCharacterFacade;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    private final EquipmentExpectationCalculatorFactory calculatorFactory;
    private final EquipmentExpectationSummaryRepository summaryRepository;
    private final StarforceLookupTable starforceLookupTable;
    private final LogicExecutor executor;
    private final Executor equipmentExecutor;
    private final Executor presetExecutor;  // #266 P1-2: 프리셋 병렬 계산용 Executor
    private final ObjectMapper objectMapper;
    private final Cache expectationCache;  // #240 V4: L1/L2 GZIP 캐시
    private final TieredCacheManager tieredCacheManager;  // #264: L1 Fast Path용
    private final MeterRegistry meterRegistry;  // #264: Fast Path 메트릭용
    private final ExpectationWriteBackBuffer writeBackBuffer;  // #266 P1-3: Write-Behind 버퍼

    public EquipmentExpectationServiceV4(
            GameCharacterFacade gameCharacterFacade,
            EquipmentDataProvider equipmentProvider,
            EquipmentStreamingParser streamingParser,
            EquipmentExpectationCalculatorFactory calculatorFactory,
            EquipmentExpectationSummaryRepository summaryRepository,
            StarforceLookupTable starforceLookupTable,
            LogicExecutor executor,
            @Qualifier("equipmentProcessingExecutor") Executor equipmentExecutor,
            @Qualifier("presetCalculationExecutor") Executor presetExecutor,  // #266 P1-2
            ObjectMapper objectMapper,
            TieredCacheManager tieredCacheManager,
            ExpectationWriteBackBuffer writeBackBuffer) {  // #266 P1-3
        this.gameCharacterFacade = gameCharacterFacade;
        this.equipmentProvider = equipmentProvider;
        this.streamingParser = streamingParser;
        this.calculatorFactory = calculatorFactory;
        this.summaryRepository = summaryRepository;
        this.starforceLookupTable = starforceLookupTable;
        this.executor = executor;
        this.equipmentExecutor = equipmentExecutor;
        this.presetExecutor = presetExecutor;  // #266 P1-2
        this.objectMapper = objectMapper;
        this.tieredCacheManager = tieredCacheManager;
        this.meterRegistry = tieredCacheManager.getMeterRegistry();  // #264: Fast Path 메트릭
        this.expectationCache = tieredCacheManager.getCache(CACHE_NAME);  // #240 V4: L1/L2 캐시 주입
        this.writeBackBuffer = writeBackBuffer;  // #266 P1-3
    }

    private static final long ASYNC_TIMEOUT_SECONDS = 30L;
    private static final long DATA_LOAD_TIMEOUT_SECONDS = 10L;

    /**
     * 캐릭터 기대값 계산 (비동기)
     *
     * <h3>SRE 안전 장치 (#240 Red Agent)</h3>
     * <ul>
     *   <li>전체 타임아웃: 30초</li>
     *   <li>무한 대기 방지</li>
     * </ul>
     *
     * @param userIgn 캐릭터 IGN
     * @return V4 기대값 응답
     */
    @TraceLog
    public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(String userIgn) {
        return calculateExpectationAsync(userIgn, false);
    }

    /**
     * 캐릭터 기대값 계산 (비동기, force 옵션)
     *
     * @param userIgn 캐릭터 IGN
     * @param force true: 캐시 무시하고 강제 재계산, false: 캐시 사용
     * @return V4 기대값 응답
     */
    @TraceLog
    public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(String userIgn, boolean force) {
        return CompletableFuture.supplyAsync(
                        () -> calculateExpectation(userIgn, force),
                        equipmentExecutor
                )
                .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);  // SRE: 무한 대기 방지
    }

    /**
     * GZIP 압축된 기대값 응답 반환 (비동기) (#262 성능 최적화)
     *
     * <h3>성능 이점</h3>
     * <ul>
     *   <li>서버: JSON 역직렬화 스킵 → CPU 절감</li>
     *   <li>네트워크: 압축 상태 전송 → 대역폭 절감</li>
     *   <li>클라이언트: 브라우저가 자동 압축 해제</li>
     * </ul>
     *
     * @param userIgn 캐릭터 IGN
     * @param force true: 캐시 무시, false: 캐시 사용
     * @return GZIP 압축된 바이트 배열
     */
    @TraceLog
    public CompletableFuture<byte[]> getGzipExpectationAsync(String userIgn, boolean force) {
        return CompletableFuture.supplyAsync(
                        () -> getGzipExpectation(userIgn, force),
                        equipmentExecutor
                )
                .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 캐릭터 기대값 계산 (동기, 기본 - 캐시 사용)
     */
    @Transactional
    public EquipmentExpectationResponseV4 calculateExpectation(String userIgn) {
        return calculateExpectation(userIgn, false);
    }

    /**
     * 캐릭터 기대값 계산 (동기, force 옵션)
     *
     * <h3>Issue #262: Singleflight 패턴 적용</h3>
     * <ul>
     *   <li>TieredCache.get(key, Callable)로 Cache Stampede 방지</li>
     *   <li>1,000개 동시 요청 → 1개 계산 + 999개 대기</li>
     *   <li>StarforceLookupTable 초기화 확인</li>
     * </ul>
     *
     * @param userIgn 캐릭터 IGN
     * @param force true: 캐시 무시하고 강제 재계산, false: Singleflight 캐시 사용
     */
    @Transactional
    public EquipmentExpectationResponseV4 calculateExpectation(String userIgn, boolean force) {
        // SRE: 초기화 상태 확인 (#240 Red Agent)
        if (!starforceLookupTable.isInitialized()) {
            throw new IllegalStateException("StarforceLookupTable not initialized. Please wait for server startup to complete.");
        }

        return getOrCalculateExpectation(userIgn, force);
    }

    /**
     * Singleflight 패턴으로 기대값 조회 또는 계산 (#262)
     *
     * <h3>5-Agent Council 합의사항</h3>
     * <ul>
     *   <li>🔵 Blue: TieredCache.get(key, Callable) 기존 인프라 활용</li>
     *   <li>🟢 Green: 98% CPU 절감 (1,000 parallel → 1 calculation)</li>
     *   <li>🟢 Green: GZIP 압축 (200KB → 15KB) - 캐시 효율화</li>
     *   <li>🔴 Red: Graceful Degradation - 캐시 장애 시 직접 계산</li>
     * </ul>
     *
     * <h3>핵심 원칙 (#262 Fix)</h3>
     * <ul>
     *   <li>캐시 히트: 압축 해제 후 반환 (계산 절대 금지)</li>
     *   <li>캐시 미스: TieredCache Callable 내에서만 계산</li>
     *   <li>압축 해제 실패: 예외 발생 (재계산 X)</li>
     * </ul>
     */
    private EquipmentExpectationResponseV4 getOrCalculateExpectation(String userIgn, boolean force) {
        // force=true: 캐시 무시하고 직접 계산
        if (force) {
            return doCalculateExpectation(userIgn);
        }

        // TieredCache.get(key, Callable):
        // - Cache Hit: 캐시된 Base64 String 반환 (Callable 실행 안함!)
        // - Cache Miss: Callable 실행 → 계산 → 캐시 저장 → 반환
        //
        // 주의: executor 래핑 금지 - @Transactional 컨텍스트 전파 필수
        // Note: Base64 String으로 저장하여 Redis 직렬화기 호환성 보장 (#262)
        String compressedBase64 = expectationCache.get(userIgn, () -> {
            // ★ 이 블록은 캐시 미스 시에만 실행됨 ★
            log.info("[V4] Cache MISS - 계산 시작: {}", userIgn);
            EquipmentExpectationResponseV4 response = doCalculateExpectation(userIgn);
            return compressAndSerialize(response, userIgn);
        });

        // 캐시 히트: 압축 해제만 수행 (계산 없음)
        return decompressCachedResponse(compressedBase64, userIgn);
    }

    /**
     * Response → JSON → GZIP → Base64 String 변환 (#262)
     *
     * <p>200KB JSON → 약 15-20KB GZIP → Base64 (Redis 직렬화기 호환)</p>
     * <p>주의: 트랜잭션 컨텍스트 유지를 위해 executor 래핑 금지</p>
     *
     * <h4>CLAUDE.md Section 12 준수</h4>
     * <ul>
     *   <li>try-catch 사용 금지 → throws 선언</li>
     *   <li>Callable 내에서 호출 → TieredCache가 예외 처리</li>
     * </ul>
     *
     * <h4>Base64 사용 이유 (#262)</h4>
     * <p>RedisCacheManager가 GenericJackson2JsonRedisSerializer를 기본 사용하여
     * byte[]가 String으로 변환되는 문제 해결</p>
     *
     * @throws Exception JsonProcessingException 또는 CompressionException
     */
    private String compressAndSerialize(EquipmentExpectationResponseV4 response, String userIgn)
            throws Exception {
        String json = objectMapper.writeValueAsString(response);
        byte[] compressed = GzipUtils.compress(json);
        String base64 = java.util.Base64.getEncoder().encodeToString(compressed);
        log.debug("[V4] GZIP+Base64 압축 완료: {} (원본: {}KB → 압축: {}KB → Base64: {}KB)",
                userIgn, json.length() / 1024, compressed.length / 1024, base64.length() / 1024);
        return base64;
    }

    /**
     * GZIP 압축된 기대값 응답 반환 (#262 성능 최적화)
     *
     * <h3>사용 사례</h3>
     * <p>클라이언트가 Accept-Encoding: gzip 지원 시,
     * 서버에서 압축 해제 없이 GZIP 바이트 직접 반환</p>
     *
     * <h3>성능 이점</h3>
     * <ul>
     *   <li>서버 CPU 절감: JSON 파싱/역직렬화 스킵</li>
     *   <li>응답 시간 단축: 압축 해제 오버헤드 제거</li>
     *   <li>네트워크 효율: 압축된 상태로 전송 (200KB → 15KB)</li>
     * </ul>
     *
     * <h3>성능 최적화 (#262)</h3>
     * <p>Base64 디코딩은 단순 연산이므로 executor 래핑 없이 직접 수행</p>
     *
     * @param userIgn 캐릭터 IGN
     * @param force true: 캐시 무시하고 재계산, false: 캐시 사용
     * @return GZIP 압축된 바이트 배열
     */
    public byte[] getGzipExpectation(String userIgn, boolean force) {
        // SRE: 초기화 상태 확인
        if (!starforceLookupTable.isInitialized()) {
            throw new IllegalStateException("StarforceLookupTable not initialized.");
        }

        // force=true: 캐시 무시하고 직접 계산 → GZIP 반환
        if (force) {
            EquipmentExpectationResponseV4 response = doCalculateExpectation(userIgn);
            return compressToGzipBytes(response, userIgn);
        }

        // Singleflight 패턴: 동일한 캐시 사용 (getOrCalculateExpectation과 공유)
        String compressedBase64 = expectationCache.get(userIgn, () -> {
            log.info("[V4] Cache MISS (GZIP) - 계산 시작: {}", userIgn);
            EquipmentExpectationResponseV4 response = doCalculateExpectation(userIgn);
            return compressAndSerialize(response, userIgn);
        });

        // Base64 → GZIP byte[] 직접 변환 (executor 오버헤드 제거)
        if (compressedBase64 == null || compressedBase64.isEmpty()) {
            throw new IllegalStateException("[V4] 캐시 데이터 없음: " + userIgn);
        }

        log.debug("[V4] GZIP Cache HIT: {} ({}KB)", userIgn, compressedBase64.length() / 1024);
        return java.util.Base64.getDecoder().decode(compressedBase64);
    }

    /**
     * L1 캐시 직접 조회 - Fast Path (#264 성능 최적화)
     *
     * <h3>5-Agent Council 합의사항 (#264)</h3>
     * <ul>
     *   <li>🟢 Green: L1 히트 시 Executor/LogicExecutor 오버헤드 완전 제거</li>
     *   <li>🔵 Blue: OCP 준수 - 기존 코드 수정 없음, 새 메서드 추가</li>
     *   <li>🔴 Red: L1 미스 시 기존 경로로 Graceful Fallback</li>
     *   <li>🟣 Purple: CLAUDE.md 준수 - Optional 체이닝, try-catch 없음</li>
     * </ul>
     *
     * <h3>Context7 Best Practice: Caffeine getIfPresent()</h3>
     * <p>값이 있으면 즉시 반환, 없으면 null (loader 실행 X)</p>
     *
     * <h3>성능 이점</h3>
     * <ul>
     *   <li>L1 히트 시: 0.1ms (스레드풀 경합 없음)</li>
     *   <li>기존 경로: 5-10ms (Executor → TieredCache → LogicExecutor)</li>
     * </ul>
     *
     * @param userIgn 캐릭터 IGN
     * @return GZIP 바이트 (L1 히트 시), Empty (L1 미스 시)
     */
    public Optional<byte[]> getGzipFromL1CacheDirect(String userIgn) {
        Cache l1Cache = tieredCacheManager.getL1CacheDirect(CACHE_NAME);
        if (l1Cache == null) {
            recordFastPathMiss();
            return Optional.empty();
        }

        // Caffeine getIfPresent() 패턴: 값이 있으면 반환, 없으면 null
        Cache.ValueWrapper wrapper = l1Cache.get(userIgn);
        if (wrapper == null || wrapper.get() == null) {
            recordFastPathMiss();
            return Optional.empty();
        }

        // Base64 → GZIP byte[] 변환 (단순 연산, 예외 가능성 낮음)
        String base64 = (String) wrapper.get();
        byte[] gzipBytes = java.util.Base64.getDecoder().decode(base64);

        recordFastPathHit();
        log.debug("[V4] L1 Fast Path HIT: {} ({}KB)", userIgn, gzipBytes.length / 1024);
        return Optional.of(gzipBytes);
    }

    // ==================== Fast Path Metrics (#264) ====================

    private void recordFastPathHit() {
        meterRegistry.counter("cache.l1.fast_path", "result", "hit").increment();
    }

    private void recordFastPathMiss() {
        meterRegistry.counter("cache.l1.fast_path", "result", "miss").increment();
    }

    /**
     * Response → JSON → GZIP byte[] 직접 변환 (force=true 용)
     */
    private byte[] compressToGzipBytes(EquipmentExpectationResponseV4 response, String userIgn) {
        TaskContext context = TaskContext.of("ExpectationV4", "CompressForce", userIgn);
        return executor.executeWithTranslation(
                () -> {
                    String json = objectMapper.writeValueAsString(response);
                    return GzipUtils.compress(json);
                },
                (e, ctx) -> new IllegalStateException(
                        String.format("[V4] GZIP 생성 실패 [%s]: %s", ctx.toTaskName(), userIgn), e),
                context
        );
    }

    /**
     * Base64 → GZIP byte[] → JSON → Response 압축 해제 (#262 Fix)
     *
     * <h3>핵심 원칙: 캐시 히트 시 계산 절대 금지</h3>
     * <ul>
     *   <li>압축 해제 성공: 캐시된 응답 반환</li>
     *   <li>압축 해제 실패: 예외 발생 (재계산 X)</li>
     *   <li>compressedBase64가 null: IllegalStateException (캐시 미스는 Callable에서 처리됨)</li>
     * </ul>
     *
     * <h4>CLAUDE.md Section 12 패턴 6 준수</h4>
     * <p>try-catch 금지 → executeWithTranslation()으로 예외 변환</p>
     */
    private EquipmentExpectationResponseV4 decompressCachedResponse(String compressedBase64, String userIgn) {
        return executor.executeWithTranslation(
                () -> decompressInternal(compressedBase64, userIgn),
                (e, context) -> new IllegalStateException(
                        String.format("[V4] GZIP 압축 해제 실패 [%s]: %s", context.toTaskName(), userIgn), e),
                TaskContext.of("ExpectationV4", "Decompress", userIgn)
        );
    }

    /**
     * Base64 → GZIP 압축 해제 내부 로직 (CLAUDE.md Section 15: 람다 추출)
     *
     * @throws Exception JsonProcessingException 또는 CompressionException
     */
    private EquipmentExpectationResponseV4 decompressInternal(String compressedBase64, String userIgn)
            throws Exception {
        // compressedBase64가 null이면 캐시 로직에 버그가 있는 것
        if (compressedBase64 == null || compressedBase64.isEmpty()) {
            throw new IllegalStateException(
                    String.format("[V4] 캐시 데이터 없음 - 캐시 로직 오류 의심: %s", userIgn));
        }

        byte[] compressed = java.util.Base64.getDecoder().decode(compressedBase64);
        String json = GzipUtils.decompress(compressed);
        EquipmentExpectationResponseV4 response = objectMapper.readValue(json, EquipmentExpectationResponseV4.class);

        log.debug("[V4] Cache HIT (Base64+GZIP): {} (Base64: {}KB → 압축: {}KB → 원본: {}KB)",
                userIgn, compressedBase64.length() / 1024, compressed.length / 1024, json.length() / 1024);

        return rebuildWithCacheFlag(response, true);
    }

    /**
     * fromCache 플래그 변경하여 응답 재생성 (#262)
     */
    private EquipmentExpectationResponseV4 rebuildWithCacheFlag(EquipmentExpectationResponseV4 original, boolean fromCache) {
        return EquipmentExpectationResponseV4.builder()
                .userIgn(original.getUserIgn())
                .calculatedAt(original.getCalculatedAt())
                .fromCache(fromCache)
                .totalExpectedCost(original.getTotalExpectedCost())
                .totalCostText(original.getTotalCostText())
                .totalCostBreakdown(original.getTotalCostBreakdown())
                .maxPresetNo(original.getMaxPresetNo())
                .presets(original.getPresets())
                .build();
    }

    /**
     * 실제 기대값 계산 로직 (Singleflight Leader가 실행)
     *
     * <h3>책임 분리 (SRP)</h3>
     * <ul>
     *   <li>캐릭터 조회 → 장비 로드 → 계산 → DB 저장 → 응답 생성</li>
     *   <li>캐시 로직은 getOrCalculateExpectation()에서 처리</li>
     * </ul>
     */
    private EquipmentExpectationResponseV4 doCalculateExpectation(String userIgn) {
        TaskContext context = TaskContext.of("ExpectationV4", "Calculate", userIgn);

        return executor.execute(() -> {
            // 1. 캐릭터 조회
            GameCharacter character = gameCharacterFacade.findCharacterByUserIgn(userIgn);

            // 2. 장비 데이터 로드 (Streaming)
            byte[] equipmentData = loadEquipmentData(character);

            // 3. 프리셋별 계산
            List<PresetExpectation> presetResults = calculateAllPresets(equipmentData, character);

            // 4. 최대 기대값 프리셋 찾기
            PresetExpectation maxPreset = findMaxPreset(presetResults);

            // 5. DB 저장 (요약 데이터)
            saveResults(character.getId(), presetResults);

            // 6. 응답 생성
            return buildResponse(userIgn, maxPreset, presetResults, false);
        }, context);
    }

    /**
     * 최대 기대값 프리셋 찾기 (#262 리팩토링: 메서드 추출)
     */
    private PresetExpectation findMaxPreset(List<PresetExpectation> presetResults) {
        return presetResults.stream()
                .max((p1, p2) -> p1.getTotalExpectedCost().compareTo(p2.getTotalExpectedCost()))
                .orElse(null);
    }

    /**
     * 응답 객체 생성 (#262 리팩토링: 메서드 추출)
     */
    private EquipmentExpectationResponseV4 buildResponse(String userIgn, PresetExpectation maxPreset,
                                                          List<PresetExpectation> presetResults, boolean fromCache) {
        BigDecimal totalCost = maxPreset != null ? maxPreset.getTotalExpectedCost() : BigDecimal.ZERO;
        CostBreakdownDto totalBreakdown = maxPreset != null ? maxPreset.getCostBreakdown() : CostBreakdownDto.empty();
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

    /**
     * 장비 데이터 로드
     *
     * <h3>SRE 안전 장치 (#240 Red Agent)</h3>
     * <ul>
     *   <li>API 호출 타임아웃: 10초</li>
     *   <li>join() 무한 대기 방지</li>
     * </ul>
     */
    private byte[] loadEquipmentData(GameCharacter character) {
        if (character.getEquipment() != null && character.getEquipment().getJsonContent() != null) {
            return character.getEquipment().getJsonContent().getBytes();
        }
        // API에서 직접 로드 (fallback) - 타임아웃 적용
        return equipmentProvider.getRawEquipmentData(character.getOcid())
                .orTimeout(DATA_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)  // SRE: 무한 대기 방지
                .join();
    }

    /**
     * 모든 프리셋 병렬 계산 (#266 P1-2: 300ms → 110ms 성능 개선)
     *
     * <h3>5-Agent Council 합의</h3>
     * <ul>
     *   <li>Green (Performance): CompletableFuture로 3x 성능 개선</li>
     *   <li>Red (SRE): presetCalculationExecutor로 Deadlock 방지</li>
     *   <li>Blue (Architect): 스트림 기반 함수형 구현</li>
     * </ul>
     *
     * <p>각 프리셋별로 장비 데이터 파싱 및 기대값 계산을 병렬로 수행합니다.</p>
     */
    private List<PresetExpectation> calculateAllPresets(byte[] equipmentData, GameCharacter character) {
        // 프리셋 1, 2, 3 병렬 계산
        List<CompletableFuture<PresetExpectation>> futures = IntStream.rangeClosed(1, 3)
                .mapToObj(presetNo -> CompletableFuture.supplyAsync(
                        () -> calculatePreset(equipmentData, presetNo),
                        presetExecutor
                ))
                .toList();

        // 모든 Future 완료 대기 및 결과 수집
        return futures.stream()
                .map(this::joinPresetFuture)
                .filter(preset -> !preset.getItems().isEmpty())  // 빈 프리셋 제외
                .toList();
    }

    /**
     * 프리셋 Future 결과 조회 (타임아웃 포함)
     *
     * <h4>Red Agent: 타임아웃으로 무한 대기 방지</h4>
     *
     * @param future 프리셋 계산 Future
     * @return 계산된 프리셋 결과
     */
    private PresetExpectation joinPresetFuture(CompletableFuture<PresetExpectation> future) {
        return executor.execute(
                () -> future.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                TaskContext.of("V4", "PresetJoin")
        );
    }

    /**
     * 단일 프리셋 계산 (#240 V4: 프리셋별 파싱 + 놀장/에디셔널 지원)
     */
    private PresetExpectation calculatePreset(byte[] equipmentData, int presetNo) {
        // 프리셋별 장비 파싱 (preset 1~3)
        var cubeInputs = streamingParser.parseCubeInputsForPreset(equipmentData, presetNo);

        List<ItemExpectationV4> itemResults = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        CostBreakdownDto totalBreakdown = CostBreakdownDto.empty();

        for (var cubeInput : cubeInputs) {
            // 놀장 여부 판별 (#240 V4)
            boolean isNoljang = cubeInput.isNoljangEquipment();

            // 목표 스타 결정 (#240 V4: JSON starforce 필드 값 사용)
            // 놀장: 최대 15성 제한, 일반: JSON에서 파싱된 starforce 값
            int parsedStarforce = cubeInput.getStarforce();
            int targetStar = isNoljang
                    ? Math.min(parsedStarforce, NoljangProbabilityTable.MAX_NOLJANG_STAR)
                    : parsedStarforce;

            // 보조무기 잠재 부위 결정 (#240 V4)
            String potentialPart = SecondaryWeaponCategory.resolvePotentialPart(
                    cubeInput.getPart(), cubeInput.getItemEquipmentPart());

            // V4 계산 입력 생성 (확장 필드 포함)
            EquipmentCalculationInput input = EquipmentCalculationInput.builder()
                    .itemName(cubeInput.getItemName())
                    .itemPart(potentialPart)
                    .itemEquipmentPart(cubeInput.getItemEquipmentPart())
                    .itemIcon(cubeInput.getItemIcon())
                    .itemLevel(cubeInput.getLevel())
                    .presetNo(presetNo)
                    .isNoljang(isNoljang)
                    // 잠재능력 (윗잠)
                    .potentialGrade(cubeInput.getGrade())
                    .potentialOptions(cubeInput.getOptions())
                    // 에디셔널 잠재능력 (아랫잠)
                    .additionalPotentialGrade(cubeInput.getAdditionalGrade())
                    .additionalPotentialOptions(cubeInput.getAdditionalOptions())
                    // 스타포스 (0성 → targetStar)
                    .currentStar(0)
                    .targetStar(targetStar)
                    .build();

            // 계산기 생성 및 계산
            EquipmentExpectationCalculator calculator = calculatorFactory.createFullCalculator(input);
            BigDecimal itemCost = calculator.calculateCost();
            var costBreakdown = calculator.getDetailedCosts();

            // 스타포스 기대값 (파괴방지 O/X) 계산 (#240 V4: 놀장 분기)
            StarforceExpectationDto starforceExpectation = calculateStarforceExpectation(
                    input.getCurrentStar(), input.getTargetStar(), input.getItemLevel(), isNoljang);

            // 큐브별 기대값 생성 (#240 V4: trials를 CostBreakdown에서 가져옴, potential 텍스트 추가)
            String potentialText = formatPotentialOptions(input.getPotentialOptions());
            String additionalPotentialText = formatPotentialOptions(input.getAdditionalPotentialOptions());

            CubeExpectationDto blackCubeExpectation = buildCubeExpectation(
                    costBreakdown.blackCubeCost(), costBreakdown.blackCubeTrials(),
                    input.getPotentialGrade(), "LEGENDARY", potentialText);
            CubeExpectationDto additionalCubeExpectation = buildCubeExpectation(
                    costBreakdown.additionalCubeCost(), costBreakdown.additionalCubeTrials(),
                    input.getAdditionalPotentialGrade(), "LEGENDARY", additionalPotentialText);

            // 결과 수집
            ItemExpectationV4 itemResult = ItemExpectationV4.builder()
                    .itemName(input.getItemName())
                    .itemIcon(input.getItemIcon())
                    .itemPart(input.getItemPart())
                    .itemLevel(input.getItemLevel())
                    .expectedCost(itemCost)
                    .expectedCostText(CostFormatter.format(itemCost))
                    .costBreakdown(CostBreakdownDto.from(costBreakdown))
                    .enhancePath(calculator.getEnhancePath())
                    .potentialGrade(input.getPotentialGrade())
                    .additionalPotentialGrade(input.getAdditionalPotentialGrade())
                    .currentStar(input.getCurrentStar())
                    .targetStar(input.getTargetStar())
                    .isNoljang(isNoljang)
                    .blackCubeExpectation(blackCubeExpectation)
                    .additionalCubeExpectation(additionalCubeExpectation)
                    .starforceExpectation(starforceExpectation)
                    .build();

            itemResults.add(itemResult);
            totalCost = totalCost.add(itemCost);
            totalBreakdown = totalBreakdown.add(CostBreakdownDto.from(costBreakdown));
        }

        return PresetExpectation.builder()
                .presetNo(presetNo)
                .totalExpectedCost(totalCost)
                .totalCostText(CostFormatter.format(totalCost))
                .costBreakdown(totalBreakdown)
                .items(itemResults)
                .build();
    }

    /**
     * 큐브 기대값 DTO 생성 (#240 V4)
     *
     * <p>trials는 데코레이터에서 이미 정수로 반올림되어 전달됩니다.</p>
     * <p>cost도 반올림된 trials로 계산되어 전달됩니다.</p>
     *
     * @param cost 기대 비용 (반올림된 trials로 계산됨)
     * @param trials 기대 시도 횟수 (정수)
     * @param currentGrade 현재 등급
     * @param targetGrade 목표 등급
     * @param potentialText 현재 잠재능력 텍스트
     */
    private CubeExpectationDto buildCubeExpectation(BigDecimal cost, BigDecimal trials,
                                                     String currentGrade, String targetGrade,
                                                     String potentialText) {
        if (cost == null || cost.compareTo(BigDecimal.ZERO) == 0) {
            return CubeExpectationDto.empty();
        }

        return CubeExpectationDto.builder()
                .expectedCost(cost)
                .expectedCostText(CostFormatter.format(cost))
                .expectedTrials(trials != null ? trials : BigDecimal.ZERO)
                .currentGrade(currentGrade)
                .targetGrade(targetGrade)
                .potential(potentialText)
                .build();
    }

    /**
     * 잠재능력 옵션 리스트를 텍스트로 변환 (#240 V4)
     *
     * <p>예: ["스킬 재사용 대기시간 -2초", "스킬 재사용 대기시간 -2초", "스킬 재사용 대기시간 -2초"]</p>
     * <p>→ "스킬 재사용 대기시간 -2초 | 스킬 재사용 대기시간 -2초 | 스킬 재사용 대기시간 -2초"</p>
     */
    private String formatPotentialOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return "";
        }
        return String.join(" | ", options);
    }

    /**
     * 스타포스 기대값 계산 (파괴방지 O/X) (#240 V4: 놀장 분기 포함)
     *
     * <p>일반 스타포스: 스타캐치 O, 썬데이메이플 O, 30% 할인 O</p>
     * <p>놀장: NoljangProbabilityTable 사용, 파괴 없음</p>
     * <p>비용은 100단위로 반올림</p>
     *
     * @param currentStar 현재 스타
     * @param targetStar 목표 스타
     * @param itemLevel 아이템 레벨
     * @param isNoljang 놀장 여부
     * @return 파괴방지 O/X 별 기대값
     */
    private StarforceExpectationDto calculateStarforceExpectation(int currentStar, int targetStar,
                                                                   int itemLevel, boolean isNoljang) {
        if (isNoljang) {
            // 놀장: NoljangProbabilityTable 사용 (파괴 없음)
            BigDecimal noljangCost = NoljangProbabilityTable.getExpectedCostFromStar(
                    currentStar, targetStar, itemLevel, true, true);
            BigDecimal roundedCost = roundToNearest100(noljangCost);
            return StarforceExpectationDto.builder()
                    .currentStar(currentStar)
                    .targetStar(targetStar)
                    .isNoljang(true)
                    .costWithoutDestroyPrevention(roundedCost)
                    .costWithoutDestroyPreventionText(CostFormatter.format(roundedCost))
                    .expectedDestroyCountWithout(BigDecimal.ZERO)  // 놀장은 파괴 없음
                    .costWithDestroyPrevention(roundedCost)        // 동일 (파괴 없음)
                    .costWithDestroyPreventionText(CostFormatter.format(roundedCost))
                    .expectedDestroyCountWith(BigDecimal.ZERO)
                    .build();
        }

        // 일반 스타포스: 기존 로직
        // 파괴방지 X (기본 옵션: 스타캐치 O, 썬데이 O, 할인 O)
        BigDecimal costWithout = starforceLookupTable.getExpectedCost(
                currentStar, targetStar, itemLevel, true, true, true, false);
        BigDecimal destroyCountWithout = starforceLookupTable.getExpectedDestroyCount(
                currentStar, targetStar, true, true, false);

        // 파괴방지 O (15-17성에만 적용)
        BigDecimal costWith = starforceLookupTable.getExpectedCost(
                currentStar, targetStar, itemLevel, true, true, true, true);
        BigDecimal destroyCountWith = starforceLookupTable.getExpectedDestroyCount(
                currentStar, targetStar, true, true, true);

        // #240 V4: 100단위 반올림
        BigDecimal roundedCostWithout = roundToNearest100(costWithout);
        BigDecimal roundedCostWith = roundToNearest100(costWith);

        return StarforceExpectationDto.builder()
                .currentStar(currentStar)
                .targetStar(targetStar)
                .isNoljang(false)
                .costWithoutDestroyPrevention(roundedCostWithout)
                .costWithoutDestroyPreventionText(CostFormatter.format(roundedCostWithout))
                .expectedDestroyCountWithout(destroyCountWithout)
                .costWithDestroyPrevention(roundedCostWith)
                .costWithDestroyPreventionText(CostFormatter.format(roundedCostWith))
                .expectedDestroyCountWith(destroyCountWith)
                .build();
    }

    /**
     * 100 단위로 반올림 (#240 V4)
     */
    private BigDecimal roundToNearest100(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 결과 저장 - Write-Behind 버퍼 적용 (#266 P1-3)
     *
     * <h3>5-Agent Council 합의</h3>
     * <ul>
     *   <li>Green (Performance): 15-30ms → 0.1ms 성능 개선 (150-300x)</li>
     *   <li>Red (SRE): 백프레셔 발생 시 동기 폴백으로 데이터 유실 방지</li>
     *   <li>Blue (Architect): 버퍼 실패 시 Graceful Degradation</li>
     * </ul>
     *
     * <h3>동작 방식</h3>
     * <ol>
     *   <li>Write-Behind 버퍼에 추가 시도</li>
     *   <li>백프레셔 발생 시 동기 DB 저장으로 폴백</li>
     * </ol>
     */
    private void saveResults(Long characterId, List<PresetExpectation> presets) {
        // Write-Behind 버퍼 시도
        boolean buffered = writeBackBuffer.offer(characterId, presets);

        if (buffered) {
            log.debug("[V4] Write-Behind 버퍼에 저장: characterId={}, presets={}",
                    characterId, presets.size());
            return;
        }

        // 백프레셔 발생 시 동기 폴백
        log.warn("[V4] Buffer backpressure - fallback to sync save: characterId={}", characterId);
        saveResultsSync(characterId, presets);
    }

    /**
     * 결과 동기 DB 저장 - Upsert 패턴 (#262)
     *
     * <h3>Issue #262: 동시성 안전 DB 저장</h3>
     * <p>MySQL `INSERT ... ON DUPLICATE KEY UPDATE`로 Race Condition 제거</p>
     *
     * <h3>용도</h3>
     * <ul>
     *   <li>Write-Behind 버퍼 백프레셔 발생 시 폴백</li>
     *   <li>@Transactional 컨텍스트 유지 필수</li>
     * </ul>
     */
    private void saveResultsSync(Long characterId, List<PresetExpectation> presets) {
        // 직접 호출: @Transactional 컨텍스트 유지 필수
        for (PresetExpectation preset : presets) {
            summaryRepository.upsertExpectationSummary(
                    characterId,
                    preset.getPresetNo(),
                    preset.getTotalExpectedCost(),
                    preset.getCostBreakdown().getBlackCubeCost(),
                    preset.getCostBreakdown().getRedCubeCost(),
                    preset.getCostBreakdown().getAdditionalCubeCost(),
                    preset.getCostBreakdown().getStarforceCost()
            );
        }
        log.debug("[V4] 동기 DB 저장 완료: characterId={}, presets={}", characterId, presets.size());
    }
}
