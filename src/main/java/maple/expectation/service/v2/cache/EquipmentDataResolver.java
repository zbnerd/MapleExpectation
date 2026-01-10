package maple.expectation.service.v2.cache;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.service.v2.worker.EquipmentDbWorker;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 장비 데이터 확보 우선순위 처리기 (Issue #158: EquipmentResponse 캐싱 제거)
 *
 * <h4>데이터 소스 우선순위</h4>
 * <ol>
 *   <li>DB JSON (15분 TTL) - 우선</li>
 *   <li>Nexon API - DB 없거나 만료 시 (비동기)</li>
 * </ol>
 *
 * <h4>Issue #158 핵심 변경</h4>
 * <p><b>Expectation 경로에서 EquipmentResponse 캐싱 완전 제거</b></p>
 * <ul>
 *   <li>L1/L2 캐시 저장 금지</li>
 *   <li>최종 결과인 TotalExpectationResponse만 캐싱</li>
 *   <li>EquipmentResponse(270KB+)가 Redis에 저장되지 않음</li>
 *   <li><b>Nexon API 호출 후 DB 저장</b> → 다음 요청에서 API 호출 최소화</li>
 * </ul>
 *
 * <h4>Codex P2 리뷰 반영</h4>
 * <p>ThreadLocal(SkipEquipmentL2CacheContext) 전파를 위해
 * expectationComputeExecutor 사용</p>
 *
 * @see TotalExpectationCacheService
 * @see EquipmentDataProvider
 * @see EquipmentDbWorker
 */
@Slf4j
@Component
public class EquipmentDataResolver {

    /** Nexon API 개별 호출 타임아웃 (초) */
    private static final int NEXON_API_TIMEOUT_SECONDS = 25;

    private final EquipmentDataProvider dataProvider;
    private final EquipmentDbWorker dbWorker;
    private final Executor expectationExecutor;

    public EquipmentDataResolver(
            EquipmentDataProvider dataProvider,
            EquipmentDbWorker dbWorker,
            @Qualifier("expectationComputeExecutor") Executor expectationExecutor) {
        this.dataProvider = dataProvider;
        this.dbWorker = dbWorker;
        this.expectationExecutor = expectationExecutor;
    }

    /**
     * 장비 데이터 비동기 확보 (DB → API 우선순위)
     *
     * <h4>우선순위 흐름</h4>
     * <ol>
     *   <li>DB JSON (15분 TTL 유효) → 직접 반환</li>
     *   <li>DB 없거나 만료 → Nexon API 호출 → DB 저장</li>
     * </ol>
     *
     * <p><b>비동기 계약 준수:</b> 동기 로직에서 예외 발생 시에도
     * CompletableFuture.failedFuture()로 반환하여 호출자의 예외 처리 일관성 보장</p>
     *
     * @param ocid 캐릭터 OCID
     * @param userIgn 사용자 IGN (로깅용)
     * @return 장비 데이터 byte[] Future
     */
    public CompletableFuture<byte[]> resolveAsync(String ocid, String userIgn) {
        try {
            return resolveAsyncInternal(ocid, userIgn);
        } catch (Exception e) {
            log.error("[DataResolver] Sync exception during resolve for ocid={}", maskOcid(ocid), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 내부 구현 (동기 예외 발생 가능)
     */
    private CompletableFuture<byte[]> resolveAsyncInternal(String ocid, String userIgn) {
        // 1) DB 조회 (15분 TTL 체크 포함)
        return dbWorker.findValidJson(ocid)
                .map(json -> {
                    log.debug("[DataResolver] DB HIT for userIgn={}", userIgn);
                    return CompletableFuture.completedFuture(json.getBytes(StandardCharsets.UTF_8));
                })
                .orElseGet(() -> {
                    // 2) DB MISS/만료 → Nexon API 호출
                    log.info("[DataResolver] DB MISS, Nexon API call required for userIgn={}", userIgn);
                    return fetchFromNexonApiAndSave(ocid);
                });
    }

    /**
     * Nexon API에서 장비 데이터 조회 후 DB 저장
     *
     * <h4>스트리밍 의도 준수</h4>
     * <ul>
     *   <li>Parser에게는 압축 상태(compressedData)로 전달</li>
     *   <li>Parser 내부에서 GZIPInputStream으로 스트리밍 해제</li>
     *   <li>200-400KB JSON을 한번에 메모리 로드하지 않음</li>
     * </ul>
     *
     * <h4>DB 저장</h4>
     * <p>GzipStringConverter가 저장 시 다시 압축하므로 decompress 필요</p>
     * <p>fire-and-forget 비동기 처리</p>
     */
    private CompletableFuture<byte[]> fetchFromNexonApiAndSave(String ocid) {
        return dataProvider.getRawEquipmentData(ocid)
                // P2 Fix: thenApplyAsync로 ThreadLocal 전파 보장 (PR #160 Codex 지적)
                // expectationExecutor에 contextPropagatingDecorator가 설정되어 있음
                .thenApplyAsync(compressedData -> {
                    // DB 저장용 decompress (GzipStringConverter가 저장 시 다시 compress)
                    // fire-and-forget: 비동기 + non-blocking
                    String json = GzipUtils.decompress(compressedData);
                    dbWorker.persistRawJson(ocid, json)
                            .exceptionally(e -> {
                                log.warn("[DataResolver] DB save failed (non-blocking): {}", e.getMessage());
                                return null;
                            });

                    // Parser에게는 압축 상태로 전달 (스트리밍 의도 유지)
                    // EquipmentStreamingParser가 GZIPInputStream으로 스트리밍 해제
                    return compressedData;
                }, expectationExecutor)
                .orTimeout(NEXON_API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * OCID 마스킹 (로깅용)
     */
    private String maskOcid(String value) {
        if (value == null || value.length() < 8) return "***";
        return value.substring(0, 4) + "***";
    }
}
