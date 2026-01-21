package maple.expectation.service.v4;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.EquipmentExpectationSummary;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.v4.EquipmentCalculationInput;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.ItemExpectationV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.repository.v2.EquipmentExpectationSummaryRepository;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculatorFactory;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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

    private static final int DEFAULT_TARGET_STAR = 22;

    private final GameCharacterFacade gameCharacterFacade;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    private final EquipmentExpectationCalculatorFactory calculatorFactory;
    private final EquipmentExpectationSummaryRepository summaryRepository;
    private final LogicExecutor executor;
    private final Executor equipmentExecutor;

    public EquipmentExpectationServiceV4(
            GameCharacterFacade gameCharacterFacade,
            EquipmentDataProvider equipmentProvider,
            EquipmentStreamingParser streamingParser,
            EquipmentExpectationCalculatorFactory calculatorFactory,
            EquipmentExpectationSummaryRepository summaryRepository,
            LogicExecutor executor,
            @Qualifier("equipmentProcessingExecutor") Executor equipmentExecutor) {
        this.gameCharacterFacade = gameCharacterFacade;
        this.equipmentProvider = equipmentProvider;
        this.streamingParser = streamingParser;
        this.calculatorFactory = calculatorFactory;
        this.summaryRepository = summaryRepository;
        this.executor = executor;
        this.equipmentExecutor = equipmentExecutor;
    }

    /**
     * 캐릭터 기대값 계산 (비동기)
     *
     * @param userIgn 캐릭터 IGN
     * @return V4 기대값 응답
     */
    @TraceLog
    public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(String userIgn) {
        return CompletableFuture.supplyAsync(
                () -> calculateExpectation(userIgn),
                equipmentExecutor
        );
    }

    /**
     * 캐릭터 기대값 계산 (동기)
     */
    @Transactional
    public EquipmentExpectationResponseV4 calculateExpectation(String userIgn) {
        TaskContext context = TaskContext.of("ExpectationV4", "Calculate", userIgn);

        return executor.execute(() -> {
            // 1. 캐시된 결과 확인
            Optional<EquipmentExpectationResponseV4> cached = findCachedResult(userIgn);
            if (cached.isPresent()) {
                log.debug("[V4] Cache HIT for {}", userIgn);
                return cached.get();
            }

            // 2. 캐릭터 조회
            GameCharacter character = gameCharacterFacade.findCharacterByUserIgn(userIgn);

            // 3. 장비 데이터 로드 (Streaming)
            byte[] equipmentData = loadEquipmentData(character);

            // 4. 프리셋별 계산
            List<PresetExpectation> presetResults = calculateAllPresets(equipmentData, character);

            // 5. 총합 계산
            BigDecimal totalCost = presetResults.stream()
                    .map(PresetExpectation::getTotalExpectedCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            CostBreakdownDto totalBreakdown = presetResults.stream()
                    .map(PresetExpectation::getCostBreakdown)
                    .reduce(CostBreakdownDto.empty(), CostBreakdownDto::add);

            // 6. DB 저장
            saveResults(character.getId(), presetResults);

            // 7. 응답 생성
            return EquipmentExpectationResponseV4.builder()
                    .userIgn(userIgn)
                    .calculatedAt(LocalDateTime.now())
                    .fromCache(false)
                    .totalExpectedCost(totalCost)
                    .totalCostBreakdown(totalBreakdown)
                    .presets(presetResults)
                    .build();
        }, context);
    }

    /**
     * 캐시된 결과 조회
     */
    private Optional<EquipmentExpectationResponseV4> findCachedResult(String userIgn) {
        List<EquipmentExpectationSummary> summaries = summaryRepository.findAllByUserIgn(userIgn);
        if (summaries.isEmpty()) {
            return Optional.empty();
        }

        // 캐시된 요약 데이터로 응답 생성
        List<PresetExpectation> presets = summaries.stream()
                .map(this::toPresetExpectation)
                .toList();

        BigDecimal totalCost = presets.stream()
                .map(PresetExpectation::getTotalExpectedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CostBreakdownDto totalBreakdown = presets.stream()
                .map(PresetExpectation::getCostBreakdown)
                .reduce(CostBreakdownDto.empty(), CostBreakdownDto::add);

        return Optional.of(EquipmentExpectationResponseV4.builder()
                .userIgn(userIgn)
                .calculatedAt(summaries.get(0).getCalculatedAt())
                .fromCache(true)
                .totalExpectedCost(totalCost)
                .totalCostBreakdown(totalBreakdown)
                .presets(presets)
                .build());
    }

    private PresetExpectation toPresetExpectation(EquipmentExpectationSummary summary) {
        return PresetExpectation.builder()
                .presetNo(summary.getPresetNo())
                .totalExpectedCost(summary.getTotalExpectedCost())
                .costBreakdown(CostBreakdownDto.builder()
                        .blackCubeCost(summary.getBlackCubeCost())
                        .redCubeCost(summary.getRedCubeCost())
                        .additionalCubeCost(summary.getAdditionalCubeCost())
                        .starforceCost(summary.getStarforceCost())
                        .build())
                .items(List.of()) // 요약 데이터에서는 아이템 상세 없음
                .build();
    }

    /**
     * 장비 데이터 로드
     */
    private byte[] loadEquipmentData(GameCharacter character) {
        if (character.getEquipment() != null && character.getEquipment().getJsonContent() != null) {
            return character.getEquipment().getJsonContent().getBytes();
        }
        // API에서 직접 로드 (fallback) - 동기 버전 사용
        return equipmentProvider.getRawEquipmentData(character.getOcid()).join();
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
     * 단일 프리셋 계산 (#240 V4: 프리셋별 파싱)
     */
    private PresetExpectation calculatePreset(byte[] equipmentData, int presetNo) {
        // 프리셋별 장비 파싱 (preset 1~3)
        var cubeInputs = streamingParser.parseCubeInputsForPreset(equipmentData, presetNo);

        List<ItemExpectationV4> itemResults = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        CostBreakdownDto totalBreakdown = CostBreakdownDto.empty();

        for (var cubeInput : cubeInputs) {
            // V4 계산 입력 생성
            EquipmentCalculationInput input = EquipmentCalculationInput.builder()
                    .itemName(cubeInput.getItemName())
                    .itemPart(cubeInput.getPart())
                    .itemLevel(cubeInput.getLevel())
                    .presetNo(presetNo)
                    .potentialGrade(cubeInput.getGrade())
                    .potentialOptions(cubeInput.getOptions())
                    .currentStar(0) // 스타포스 정보가 없으면 0으로 설정
                    .targetStar(DEFAULT_TARGET_STAR)
                    .build();

            // 계산기 생성 및 계산
            EquipmentExpectationCalculator calculator = calculatorFactory.createFullCalculator(input);
            BigDecimal itemCost = calculator.calculateCost();
            var costBreakdown = calculator.getDetailedCosts();

            // 결과 수집
            ItemExpectationV4 itemResult = ItemExpectationV4.builder()
                    .itemName(input.getItemName())
                    .itemPart(input.getItemPart())
                    .itemLevel(input.getItemLevel())
                    .expectedCost(itemCost)
                    .costBreakdown(CostBreakdownDto.from(costBreakdown))
                    .enhancePath(calculator.getEnhancePath())
                    .potentialGrade(input.getPotentialGrade())
                    .currentStar(input.getCurrentStar())
                    .targetStar(input.getTargetStar())
                    .build();

            itemResults.add(itemResult);
            totalCost = totalCost.add(itemCost);
            totalBreakdown = totalBreakdown.add(CostBreakdownDto.from(costBreakdown));
        }

        return PresetExpectation.builder()
                .presetNo(presetNo)
                .totalExpectedCost(totalCost)
                .costBreakdown(totalBreakdown)
                .items(itemResults)
                .build();
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
