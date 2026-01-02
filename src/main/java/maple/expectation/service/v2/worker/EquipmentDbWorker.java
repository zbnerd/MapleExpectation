package maple.expectation.service.v2.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

/**
 * Equipment 데이터를 비동기로 DB에 저장하는 Worker
 * <p>
 * Spring Boot 3.x의 Virtual Thread 기반 @Async를 활용하여
 * 대량의 동시 저장 요청을 효율적으로 처리합니다.
 * <p>
 * Graceful Shutdown 지원을 위해 {@link EquipmentPersistenceTracker}에
 * 모든 비동기 작업을 등록하여 추적합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDbWorker {
    private final CharacterEquipmentRepository repository;
    private final ObjectMapper objectMapper;
    private final EquipmentPersistenceTracker persistenceTracker;

    /**
     * 비동기로 Equipment 데이터를 DB에 저장합니다.
     * <p>
     * REQUIRES_NEW를 통해 호출측 트랜잭션과 무관하게 즉시 커밋합니다.
     * 이 작업이 끝나야만 404(조회 실패) 현상이 근본적으로 해결됩니다.
     * <p>
     * {@link CompletableFuture}를 반환하여 호출자가 필요 시 완료를 기다릴 수 있으며,
     * Graceful Shutdown 시 {@link EquipmentPersistenceTracker}가 모든 작업을 추적합니다.
     *
     * @param ocid     캐릭터 OCID
     * @param response Equipment 응답 데이터
     * @return 비동기 작업을 나타내는 CompletableFuture
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<Void> persist(String ocid, EquipmentResponse response) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        // Graceful Shutdown 지원: 작업 추적 등록
        persistenceTracker.trackOperation(ocid, future);

        try {
            String json = objectMapper.writeValueAsString(response);
            CharacterEquipment entity = repository.findById(ocid)
                    .orElseGet(() -> CharacterEquipment.builder().ocid(ocid).build());

            entity.updateData(json);
            repository.saveAndFlush(entity); // 즉시 물리적 저장

            log.debug("💾 [Async DB Save Success] ocid: {}", ocid);
            future.complete(null); // 성공 완료

        } catch (Exception e) {
            log.error("❌ [Async DB Save Error] ocid: {}", ocid, e);
            future.completeExceptionally(e); // 예외와 함께 완료
        }

        return future;
    }
}