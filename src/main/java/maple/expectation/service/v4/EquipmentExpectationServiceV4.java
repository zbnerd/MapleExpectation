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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * V4 장비 기대값 서비스 (#240)
 *
 * <h3>5-Agent Council 합의사항</h3>
 * <ul>
 *   <li>🔴 Red (SRE): 전용 Executor 사용 (equipmentProcessingExecutor)</li>
 *   <li>🟣 Purple (Auditor): BigDecimal 정밀 계산</li>
 *   <li>🟢 Green (Performance): DB 저장으로 Buffer Pool 오염 방지</li>
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
    private final ObjectMapper objectMapper;
    private final Cache expectationCache;  // #240 V4: L1/L2 GZIP 캐시

    public EquipmentExpectationServiceV4(
            GameCharacterFacade gameCharacterFacade,
            EquipmentDataProvider equipmentProvider,
            EquipmentStreamingParser streamingParser,
            EquipmentExpectationCalculatorFactory calculatorFactory,
            EquipmentExpectationSummaryRepository summaryRepository,
            StarforceLookupTable starforceLookupTable,
            LogicExecutor executor,
            @Qualifier("equipmentProcessingExecutor") Executor equipmentExecutor,
            ObjectMapper objectMapper,
            CacheManager cacheManager) {
        this.gameCharacterFacade = gameCharacterFacade;
        this.equipmentProvider = equipmentProvider;
        this.streamingParser = streamingParser;
        this.calculatorFactory = calculatorFactory;
        this.summaryRepository = summaryRepository;
        this.starforceLookupTable = starforceLookupTable;
        this.executor = executor;
        this.equipmentExecutor = equipmentExecutor;
        this.objectMapper = objectMapper;
        this.expectationCache = cacheManager.getCache(CACHE_NAME);  // #240 V4: L1/L2 캐시 주입
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
     * 캐릭터 기대값 계산 (동기, 기본 - 캐시 사용)
     */
    @Transactional
    public EquipmentExpectationResponseV4 calculateExpectation(String userIgn) {
        return calculateExpectation(userIgn, false);
    }

    /**
     * 캐릭터 기대값 계산 (동기, force 옵션)
     *
     * <h3>SRE 안전 장치 (#240 Red Agent)</h3>
     * <ul>
     *   <li>StarforceLookupTable 초기화 확인</li>
     *   <li>초기화 미완료 시 예외 발생</li>
     * </ul>
     *
     * @param userIgn 캐릭터 IGN
     * @param force true: 캐시 무시하고 강제 재계산 (아이템 상세 포함), false: 캐시 사용 (요약만)
     */
    @Transactional
    public EquipmentExpectationResponseV4 calculateExpectation(String userIgn, boolean force) {
        // SRE: 초기화 상태 확인 (#240 Red Agent)
        if (!starforceLookupTable.isInitialized()) {
            throw new IllegalStateException("StarforceLookupTable not initialized. Please wait for server startup to complete.");
        }

        TaskContext context = TaskContext.of("ExpectationV4", "Calculate", userIgn);

        return executor.execute(() -> {
            // 1. 캐시된 결과 확인 (force=true면 캐시 무시)
            if (!force) {
                Optional<EquipmentExpectationResponseV4> cached = findCachedResult(userIgn);
                if (cached.isPresent()) {
                    log.debug("[V4] Cache HIT for {}", userIgn);
                    return cached.get();
                }
            }

            // 2. 캐릭터 조회
            GameCharacter character = gameCharacterFacade.findCharacterByUserIgn(userIgn);

            // 3. 장비 데이터 로드 (Streaming)
            byte[] equipmentData = loadEquipmentData(character);

            // 4. 프리셋별 계산
            List<PresetExpectation> presetResults = calculateAllPresets(equipmentData, character);

            // 5. 최대 기대값 프리셋 찾기 (#240 V4: 합산 → 최대값)
            PresetExpectation maxPreset = presetResults.stream()
                    .max((p1, p2) -> p1.getTotalExpectedCost().compareTo(p2.getTotalExpectedCost()))
                    .orElse(null);

            BigDecimal totalCost = maxPreset != null ? maxPreset.getTotalExpectedCost() : BigDecimal.ZERO;
            CostBreakdownDto totalBreakdown = maxPreset != null ? maxPreset.getCostBreakdown() : CostBreakdownDto.empty();
            int maxPresetNo = maxPreset != null ? maxPreset.getPresetNo() : 0;

            // 6. DB 저장 (요약 데이터)
            saveResults(character.getId(), presetResults);

            // 7. 응답 생성 (비용 텍스트 포맷 포함)
            EquipmentExpectationResponseV4 response = EquipmentExpectationResponseV4.builder()
                    .userIgn(userIgn)
                    .calculatedAt(LocalDateTime.now())
                    .fromCache(false)
                    .totalExpectedCost(totalCost)
                    .totalCostText(CostFormatter.format(totalCost))
                    .totalCostBreakdown(totalBreakdown)
                    .maxPresetNo(maxPresetNo)  // #240 V4: 최대 기대값 프리셋 번호
                    .presets(presetResults)
                    .build();

            // 8. L1/L2 캐시에 GZIP 압축 저장 (#240 V4)
            saveToGzipCache(userIgn, response);

            return response;
        }, context);
    }

    /**
     * L1/L2 GZIP 압축 캐시 조회 (#240 V4)
     *
     * <h3>캐시 전략</h3>
     * <ul>
     *   <li>L1 (Caffeine) → L2 (Redis) 순차 조회</li>
     *   <li>GZIP 압축된 byte[] 저장</li>
     *   <li>캐시 히트 시 압축 해제 후 JSON 역직렬화</li>
     * </ul>
     *
     * @param userIgn 캐릭터 IGN
     * @return 캐시된 응답 (없으면 Optional.empty())
     */
    private Optional<EquipmentExpectationResponseV4> findCachedResult(String userIgn) {
        if (expectationCache == null) {
            return Optional.empty();
        }

        TaskContext context = TaskContext.of("ExpectationV4", "CacheGet", userIgn);

        return Optional.ofNullable(executor.executeOrDefault(
                () -> {
                    byte[] compressed = expectationCache.get(userIgn, byte[].class);
                    if (compressed == null || compressed.length == 0) {
                        return null;
                    }
                    return decompressAndDeserialize(compressed, userIgn);
                },
                null,
                context
        ));
    }

    /**
     * GZIP 압축 해제 및 JSON 역직렬화 (#240 V4)
     */
    private EquipmentExpectationResponseV4 decompressAndDeserialize(byte[] compressed, String userIgn) throws Exception {
        String json = GzipUtils.decompress(compressed);
        EquipmentExpectationResponseV4 response = objectMapper.readValue(json, EquipmentExpectationResponseV4.class);

        log.debug("[V4] GZIP 캐시 히트: {} (압축: {}KB → 원본: {}KB)",
                userIgn, compressed.length / 1024, json.length() / 1024);

        // fromCache 플래그를 true로 재설정
        return EquipmentExpectationResponseV4.builder()
                .userIgn(response.getUserIgn())
                .calculatedAt(response.getCalculatedAt())
                .fromCache(true)  // 캐시에서 조회됨
                .totalExpectedCost(response.getTotalExpectedCost())
                .totalCostText(response.getTotalCostText())
                .totalCostBreakdown(response.getTotalCostBreakdown())
                .maxPresetNo(response.getMaxPresetNo())
                .presets(response.getPresets())
                .build();
    }

    /**
     * L1/L2 캐시에 GZIP 압축 저장 (#240 V4)
     *
     * <h3>저장 전략</h3>
     * <ul>
     *   <li>JSON 직렬화 → GZIP 압축 → byte[] 저장</li>
     *   <li>L2(Redis) → L1(Caffeine) 순서로 저장 (일관성 보장)</li>
     *   <li>200KB JSON → 약 15~20KB 압축</li>
     * </ul>
     */
    private void saveToGzipCache(String userIgn, EquipmentExpectationResponseV4 response) {
        if (expectationCache == null) {
            return;
        }

        TaskContext context = TaskContext.of("ExpectationV4", "CachePut", userIgn);

        executor.executeVoid(() -> {
            String json = objectMapper.writeValueAsString(response);
            byte[] compressed = GzipUtils.compress(json);

            expectationCache.put(userIgn, compressed);

            log.debug("[V4] GZIP 캐시 저장: {} (원본: {}KB → 압축: {}KB)",
                    userIgn, json.length() / 1024, compressed.length / 1024);
        }, context);
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
     * 모든 프리셋 계산 (#240 V4: 프리셋 1, 2, 3 모두 계산)
     *
     * <p>각 프리셋별로 장비 데이터 파싱 및 기대값 계산</p>
     */
    private List<PresetExpectation> calculateAllPresets(byte[] equipmentData, GameCharacter character) {
        List<PresetExpectation> results = new ArrayList<>();

        // 프리셋 1, 2, 3 모두 계산
        for (int presetNo = 1; presetNo <= 3; presetNo++) {
            PresetExpectation preset = calculatePreset(equipmentData, presetNo);
            // 빈 프리셋은 제외 (장비가 없는 경우)
            if (!preset.getItems().isEmpty()) {
                results.add(preset);
            } else {
                log.debug("[V4] 프리셋 {} 장비 없음 - 스킵", presetNo);
            }
        }

        return results;
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
     * 결과 DB 저장
     */
    private void saveResults(Long characterId, List<PresetExpectation> presets) {
        for (PresetExpectation preset : presets) {
            // 기존 레코드 조회 또는 생성
            EquipmentExpectationSummary summary = summaryRepository
                    .findByGameCharacterIdAndPresetNo(characterId, preset.getPresetNo())
                    .orElseGet(() -> EquipmentExpectationSummary.builder()
                            .gameCharacterId(characterId)
                            .presetNo(preset.getPresetNo())
                            .build());

            // 값 업데이트
            summary.updateExpectation(
                    preset.getTotalExpectedCost(),
                    preset.getCostBreakdown().getBlackCubeCost(),
                    preset.getCostBreakdown().getRedCubeCost(),
                    preset.getCostBreakdown().getAdditionalCubeCost(),
                    preset.getCostBreakdown().getStarforceCost()
            );

            summaryRepository.save(summary);
        }
    }
}
