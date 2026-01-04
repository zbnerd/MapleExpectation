package maple.expectation.external.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
import maple.expectation.global.executor.strategy.ExceptionTranslator; // ✅ JSON 번역기
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Primary
@Component("resilientNexonApiClient")
public class ResilientNexonApiClient implements NexonApiClient {

    private final NexonApiClient delegate;
    private final DiscordAlertService discordAlertService;
    private final CharacterEquipmentRepository equipmentRepository;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor; // ✅ 지능형 실행기 추가

    private static final String NEXON_API = "nexonApi";

    public ResilientNexonApiClient(
            @Qualifier("realNexonApiClient") NexonApiClient delegate,
            DiscordAlertService discordAlertService,
            CharacterEquipmentRepository equipmentRepository,
            ObjectMapper objectMapper,
            LogicExecutor executor) {
        this.delegate = delegate;
        this.discordAlertService = discordAlertService;
        this.equipmentRepository = equipmentRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Override
    @ObservedTransaction("external.api.nexon.ocid")
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getOcidFallback")
    public CharacterOcidResponse getOcidByCharacterName(String name) {
        return delegate.getOcidByCharacterName(name);
    }

    @Override
    @ObservedTransaction("external.api.nexon.itemdata")
    @TimeLimiter(name = NEXON_API)
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getItemDataFallback")
    public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
        return delegate.getItemDataByOcid(ocid);
    }

    // --- Fallback Methods (박멸 완료) ---

    public CharacterOcidResponse getOcidFallback(String name, Throwable t) {
        handleIgnoreMarker(t);
        log.error("🚩 [Resilience] OCID 최종 조회 실패. 사유: {}", t.getMessage());
        throw new ExternalServiceException("넥슨 캐릭터 정보 조회 서비스");
    }

    /**
     * ✅ [완전 평탄화] 장애 대응 시나리오 가동
     * DB 조회와 알림 발송 과정을 LogicExecutor로 감싸 관측성 확보
     */
    public CompletableFuture<EquipmentResponse> getItemDataFallback(String ocid, Throwable t) {
        handleIgnoreMarker(t);
        log.warn("🚩 [Resilience] 장애 대응 시나리오 가동. 사유: {}", t.getMessage());
        TaskContext context = TaskContext.of("NexonApi", "Fallback", ocid); //

        return executor.execute(() -> {
            // 1. DB에서 만료된 캐시라도 찾기 (Scenario A)
            EquipmentResponse cachedData = equipmentRepository.findById(ocid)
                    .map(this::convertToResponse)
                    .orElse(null);

            if (cachedData != null) {
                log.warn("[Scenario A] 만료된 캐시 데이터 반환 (Degrade)");
                return CompletableFuture.completedFuture(cachedData);
            }

            // 2. 캐시도 없으면 최종 실패 및 알림 (Scenario B)
            log.error("[Scenario B] 캐시 부재. 알림 발송");
            executor.executeVoid(() ->
                            discordAlertService.sendCriticalAlert("외부 API 장애", "OCID: " + ocid, new Exception(t)),
                    TaskContext.of("Alert", "SendFailure", ocid)
            );

            throw new ExternalServiceException("넥슨 API 서비스 불가");
        }, context);
    }

    private void handleIgnoreMarker(Throwable t) {
        if (t instanceof CircuitBreakerIgnoreMarker) {
            throw (RuntimeException) t;
        }
    }

    /**
     * ✅  Jackson 파싱 try-catch 제거
     * ExceptionTranslator.forJson()을 사용하여 에러 세탁 및 관측성 확보
     */
    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        return executor.executeWithTranslation(
                () -> objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class),
                ExceptionTranslator.forJson(), //
                TaskContext.of("NexonApi", "DeserializeCache", entity.getOcid()) //
        );
    }
}