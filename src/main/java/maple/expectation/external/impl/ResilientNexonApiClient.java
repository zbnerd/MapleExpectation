package maple.expectation.external.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterBasicResponse;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.global.executor.CheckedLogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.util.ExceptionUtils;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Primary
@Component("resilientNexonApiClient")
public class ResilientNexonApiClient implements NexonApiClient {

    private final NexonApiClient delegate;
    private final DiscordAlertService discordAlertService;
    private final CharacterEquipmentRepository equipmentRepository;
    private final ObjectMapper objectMapper;
    private final CheckedLogicExecutor checkedExecutor;
    private final Executor alertTaskExecutor;

    private static final String NEXON_API = "nexonApi";

    // 외부 서비스명 상수 (메트릭/로그 키 일관성)
    private static final String SERVICE_NEXON = "넥슨 API";
    private static final String SERVICE_DISCORD = "Discord";

    public ResilientNexonApiClient(
            @Qualifier("realNexonApiClient") NexonApiClient delegate,
            DiscordAlertService discordAlertService,
            CharacterEquipmentRepository equipmentRepository,
            ObjectMapper objectMapper,
            @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor,
            @Qualifier("alertTaskExecutor") Executor alertTaskExecutor) {
        this.delegate = delegate;
        this.discordAlertService = discordAlertService;
        this.equipmentRepository = equipmentRepository;
        this.objectMapper = objectMapper;
        this.checkedExecutor = checkedExecutor;
        this.alertTaskExecutor = alertTaskExecutor;
    }

    /**
     * 캐릭터 이름으로 OCID 조회 (비동기)
     *
     * <p>Issue #195: CompletableFuture 반환으로 Reactor 체인 내 .block() 제거</p>
     * <p>TimeLimiter 추가로 비동기 작업 타임아웃 관리</p>
     */
    @Override
    @ObservedTransaction("external.api.nexon.ocid")
    @Bulkhead(name = NEXON_API)
    @TimeLimiter(name = NEXON_API)
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getOcidFallback")
    public CompletableFuture<CharacterOcidResponse> getOcidByCharacterName(String name) {
        return delegate.getOcidByCharacterName(name);
    }

    /**
     * OCID로 캐릭터 기본 정보 조회 (비동기)
     *
     * <p>Resilience4j 적용: CircuitBreaker + Retry + TimeLimiter</p>
     */
    @Override
    @ObservedTransaction("external.api.nexon.basic")
    @Bulkhead(name = NEXON_API)
    @TimeLimiter(name = NEXON_API)
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getCharacterBasicFallback")
    public CompletableFuture<CharacterBasicResponse> getCharacterBasic(String ocid) {
        return delegate.getCharacterBasic(ocid);
    }

    @Override
    @ObservedTransaction("external.api.nexon.itemdata")
    @Bulkhead(name = NEXON_API)
    @TimeLimiter(name = NEXON_API)
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getItemDataFallback")
    public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
        return delegate.getItemDataByOcid(ocid);
    }


    /**
     * OCID 조회 fallback (비동기)
     *
     * <p>Issue #195: CompletableFuture.failedFuture() 반환으로 비동기 계약 준수</p>
     */
    public CompletableFuture<CharacterOcidResponse> getOcidFallback(String name, Throwable t) {
        handleIgnoreMarker(t);
        log.error("[Resilience] OCID 최종 조회 실패. name={}", name, t);
        return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
    }

    /**
     * 캐릭터 기본 정보 조회 fallback (비동기)
     *
     * <p>Nexon API 4xx 응답(유효하지 않은 OCID 등)은 캐릭터 미존재로 처리</p>
     */
    public CompletableFuture<CharacterBasicResponse> getCharacterBasicFallback(String ocid, Throwable t) {
        handleIgnoreMarker(t);

        Throwable root = ExceptionUtils.unwrapAsyncException(t);
        if (root instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
            log.warn("[Resilience] Character basic 조회 4xx - 캐릭터 미존재 처리. ocid={}, status={}",
                    ocid, wce.getStatusCode());
            return CompletableFuture.failedFuture(new CharacterNotFoundException(ocid));
        }

        log.error("[Resilience] Character basic 최종 조회 실패. ocid={}", ocid, t);
        return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
    }

    /**
     * 장애 대응 시나리오 가동: DB 조회 → 알림 발송
     *
     * <p>비동기 계약 준수: 최종 실패 시 failedFuture 반환 (throw 금지)</p>
     * <p>알림은 best-effort: 실패해도 fallback 반환 계약을 깨지 않음</p>
     *
     * <h4>executor 사용 정책</h4>
     * <ul>
     *   <li><b>fallback 전체를 executor로 감싸지 않음</b>: "실패를 값으로 반환"하는 구조로 관측성 왜곡 방지</li>
     *   <li><b>convertToResponse는 executor 사용</b>: JSON 역직렬화 checked 예외 변환</li>
     *   <li><b>알림 발송은 executor 사용</b>: 외부 웹훅 호출 checked 예외 변환</li>
     * </ul>
     */
    public CompletableFuture<EquipmentResponse> getItemDataFallback(String ocid, Throwable t) {
        handleIgnoreMarker(t);

        // ★ P0-3: 일관된 root cause 사용 (CompletionException/ExecutionException unwrap)
        Throwable rootCause = ExceptionUtils.unwrapAsyncException(t);
        log.warn("[Resilience] 장애 대응 시나리오 가동. ocid={}", ocid, rootCause);

        // 알림용 cause 준비: Error는 이미 handleIgnoreMarker에서 throw됨
        Exception alertCause = (rootCause instanceof Exception ex) ? ex : new Exception(rootCause);

        // 1. DB에서 만료된 캐시라도 찾기 (Scenario A)
        // convertToResponse 내부에서 CheckedLogicExecutor로 JSON 역직렬화 관측성 확보
        EquipmentResponse cachedData = equipmentRepository.findById(ocid)
                .map(this::convertToResponse)
                .orElse(null);

        if (cachedData != null) {
            log.warn("[Scenario A] 만료된 캐시 데이터 반환 (Degrade)");
            return CompletableFuture.completedFuture(cachedData);
        }

        // 2. 캐시도 없으면 최종 실패 및 알림 (Scenario B)
        // 알림은 best-effort: 실패해도 fallback 반환 계약을 깨지 않음
        log.error("[Scenario B] 캐시 부재. 알림 발송 시도");
        sendAlertBestEffort(ocid, alertCause);

        // ★ P0-3 : 도메인 예외 cause는 원본 t 유지 (래퍼 컨텍스트 보존)
        // - 관측(로그/알림): rootCause 사용 (위에서 처리)
        // - 트러블슈팅: wrapper(CompletionException 등)도 의미 있으므로 원본 유지
        return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
    }

    /**
     * 알림 발송 (best-effort)
     *
     * <p>알림 실패가 fallback의 반환 계약을 깨지 않도록 비동기 분리 + 예외 흡수</p>
     * <p>전용 alertTaskExecutor 사용: commonPool 오염/경합 방지</p>
     *
     * <h4>예외 처리 정책 </h4>
     * <ul>
     *   <li><b>RejectedExecutionException</b>: 정책적 드롭 → DEBUG (정상 시나리오)</li>
     *   <li><b>기타 예외</b>: 실제 알림 실패 → WARN</li>
     * </ul>
     */
    private void sendAlertBestEffort(String ocid, Exception alertCause) {
        CompletableFuture.runAsync(() ->
                checkedExecutor.executeUncheckedVoid(
                        () -> discordAlertService.sendCriticalAlert("외부 API 장애", "OCID: " + ocid, alertCause),
                        TaskContext.of("Alert", "SendCritical", ocid),
                        e -> new ExternalServiceException(SERVICE_DISCORD, e)
                ),
                alertTaskExecutor  // commonPool 대신 전용 Executor 사용
        ).exceptionally(ex -> handleAlertFailure(ex, ocid));
    }

    /**
     * 알림 실패 처리 (Section 15: 3-Line Rule 준수)
     *
     * @param ex 알림 실패 예외
     * @param ocid 대상 캐릭터 OCID
     * @return null (exceptionally 반환값)
     */
    private Void handleAlertFailure(Throwable ex, String ocid) {
        Throwable root = ExceptionUtils.unwrapAsyncException(ex);
        if (root instanceof RejectedExecutionException) {
            log.debug("[Alert] 알림 드롭 (큐 포화, best-effort). ocid={}", ocid);
        } else {
            log.warn("[Alert] 디스코드 알림 실패 (best-effort). ocid={}", ocid, root);
        }
        return null;
    }

    /**
     * CircuitBreakerIgnoreMarker 처리 및 ADR 계약 방어
     *
     * <p>Resilience4j/CompletableFuture 경로에서 CompletionException/ExecutionException으로
     * 감싸진 예외를 unwrap한 후 Marker 여부를 판단합니다.</p>
     *
     * <p>Error는 즉시 전파하고, Marker가 RuntimeException이 아니면 IllegalStateException으로 fail-fast</p>
     */
    private void handleIgnoreMarker(Throwable t) {
        // Resilience4j/CompletableFuture wrapper unwrap
        Throwable root = ExceptionUtils.unwrapAsyncException(t);

        // ADR: Error는 어떤 상황에서든 즉시 전파
        if (root instanceof Error e) throw e;

        // Marker 처리: RuntimeException 캐스팅 방어
        if (root instanceof CircuitBreakerIgnoreMarker) {
            if (root instanceof RuntimeException re) throw re;
            throw new IllegalStateException("CircuitBreakerIgnoreMarker must be RuntimeException", root);
        }
    }

    /**
     * JSON 역직렬화: CheckedLogicExecutor.executeUnchecked로 try-catch 제거
     *
     * <p>Jackson의 JsonProcessingException(checked)을 EquipmentDataProcessingException(unchecked)으로 변환</p>
     */
    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        return checkedExecutor.executeUnchecked(
                () -> objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class),
                TaskContext.of("NexonApi", "DeserializeCache", entity.getOcid()),
                e -> new EquipmentDataProcessingException(
                        "JSON 역직렬화 실패 [ocid=" + entity.getOcid() + "]: " + e.getMessage(), e)
        );
    }
}