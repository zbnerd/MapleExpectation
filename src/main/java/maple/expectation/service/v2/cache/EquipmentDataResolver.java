package maple.expectation.service.v2.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import maple.expectation.provider.EquipmentDataProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 장비 데이터 확보 우선순위 처리기
 *
 * <h4>데이터 소스 우선순위</h4>
 * <ol>
 *   <li>L1 캐시 (Caffeine) - 가장 빠름</li>
 *   <li>DB JSON (GZIP 압축) - 중간</li>
 *   <li>Nexon API - 가장 느림 (비동기)</li>
 * </ol>
 *
 * <h4>SRP 분리 목적</h4>
 * <p>EquipmentService에서 데이터 확보 로직을 분리하여
 * 오케스트레이션과 데이터 소스 처리를 명확히 구분합니다.</p>
 *
 * @see EquipmentCacheService
 * @see EquipmentDataProvider
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDataResolver {

    /** Nexon API 개별 호출 타임아웃 (초) */
    private static final int NEXON_API_TIMEOUT_SECONDS = 25;

    private final EquipmentCacheService cacheService;
    private final EquipmentDataProvider dataProvider;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;

    /**
     * 장비 데이터 비동기 확보
     *
     * <h4>우선순위 흐름</h4>
     * <ol>
     *   <li>L1 캐시 HIT → 즉시 반환</li>
     *   <li>DB JSON 존재 → L1 캐시 저장 후 반환</li>
     *   <li>둘 다 없음 → Nexon API 호출 (비동기)</li>
     * </ol>
     *
     * <p><b>비동기 계약 준수:</b> 동기 로직에서 예외 발생 시에도
     * CompletableFuture.failedFuture()로 반환하여 호출자의 예외 처리 일관성 보장</p>
     *
     * @param ocid 캐릭터 OCID
     * @param dbJson DB에 저장된 JSON (nullable)
     * @param userIgn 사용자 IGN (로깅용)
     * @return 장비 데이터 byte[] Future
     */
    public CompletableFuture<byte[]> resolveAsync(String ocid, String dbJson, String userIgn) {
        try {
            return resolveAsyncInternal(ocid, dbJson, userIgn);
        } catch (Exception e) {
            // 동기 예외 발생 시 비동기 계약 준수를 위해 failedFuture로 래핑
            log.error("[DataResolver] Sync exception during resolve for ocid={}", maskOcid(ocid), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 내부 구현 (동기 예외 발생 가능)
     */
    private CompletableFuture<byte[]> resolveAsyncInternal(String ocid, String dbJson, String userIgn) {
        // 1) L1 캐시 확인
        Optional<EquipmentResponse> cached = cacheService.getValidCacheL1Only(ocid);
        if (cached.isPresent()) {
            log.debug("[DataResolver] L1 cache HIT for ocid={}", maskOcid(ocid));
            return CompletableFuture.completedFuture(serializeToBytes(cached.get()));
        }

        // 2) DB JSON 사용
        if (dbJson != null) {
            log.debug("[DataResolver] Using DB JSON for ocid={}", maskOcid(ocid));
            cacheService.saveCacheL1Only(ocid, deserializeToDto(dbJson));
            return CompletableFuture.completedFuture(dbJson.getBytes(StandardCharsets.UTF_8));
        }

        // 3) Nexon API 호출 (비동기 체이닝)
        log.info("[DataResolver] Nexon API call required for userIgn={}", userIgn);
        return fetchFromNexonApi(ocid);
    }

    /**
     * Nexon API에서 장비 데이터 조회 (비동기)
     */
    private CompletableFuture<byte[]> fetchFromNexonApi(String ocid) {
        return dataProvider.getEquipmentResponse(ocid)
                .orTimeout(NEXON_API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenCompose(response -> {
                    cacheService.saveCacheL1Only(ocid, response);
                    return dataProvider.getRawEquipmentData(ocid)
                            .orTimeout(NEXON_API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                });
    }

    /**
     * EquipmentResponse → byte[] 직렬화
     */
    private byte[] serializeToBytes(EquipmentResponse response) {
        return executor.executeWithTranslation(
                () -> objectMapper.writeValueAsBytes(response),
                ExceptionTranslator.forJson(),
                TaskContext.of("EquipmentDataResolver", "Serialize")
        );
    }

    /**
     * JSON String → EquipmentResponse 역직렬화
     */
    private EquipmentResponse deserializeToDto(String json) {
        return executor.executeWithTranslation(
                () -> objectMapper.readValue(json, EquipmentResponse.class),
                ExceptionTranslator.forJson(),
                TaskContext.of("EquipmentDataResolver", "Deserialize")
        );
    }

    /**
     * OCID 마스킹 (로깅용)
     */
    private String maskOcid(String value) {
        if (value == null || value.length() < 8) return "***";
        return value.substring(0, 4) + "***";
    }
}
