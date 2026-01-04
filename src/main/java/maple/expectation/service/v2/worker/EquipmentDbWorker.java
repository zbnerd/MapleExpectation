package maple.expectation.service.v2.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성 확보
import maple.expectation.global.executor.strategy.ExceptionTranslator; // ✅ 예외 세탁
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

/**
 * Equipment 데이터를 비동기로 DB에 저장하는 Worker (LogicExecutor 평탄화 완료)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDbWorker {
    private final CharacterEquipmentRepository repository;
    private final ObjectMapper objectMapper;
    private final EquipmentPersistenceTracker persistenceTracker;
    private final LogicExecutor executor; // ✅ 지능형 실행기 주입

    /**
     * ✅  비동기 저장 로직 평탄화
     * try-catch 대신 executeWithRecovery를 사용하여 Future의 상태를 결정합니다.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<Void> persist(String ocid, EquipmentResponse response) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        TaskContext context = TaskContext.of("EquipmentWorker", "AsyncPersist", ocid); //

        // 1. Graceful Shutdown 지원: 작업 추적 등록
        persistenceTracker.trackOperation(ocid, future);

        // ✅ [패턴 5] executeWithRecovery: 성공 시 complete, 실패 시 completeExceptionally 수행
        return executor.executeWithRecovery(
                () -> {
                    performSave(ocid, response, context);
                    log.debug("💾 [Async DB Save Success] ocid: {}", ocid);
                    future.complete(null); // 성공 완료 처리
                    return future;
                },
                (e) -> {
                    log.error("❌ [Async DB Save Error] ocid: {} | 사유: {}", ocid, e.getMessage());
                    future.completeExceptionally(e); // 예외와 함께 완료 처리
                    return future;
                },
                context
        );
    }

    /**
     * 헬퍼: 실제 저장 로직 (직렬화 및 DB 반영)
     */
    private void performSave(String ocid, EquipmentResponse response, TaskContext context) {
        //  Jackson 직렬화 시 발생하는 체크 예외를 도메인 예외로 세탁
        String json = executor.executeWithTranslation(
                () -> objectMapper.writeValueAsString(response),
                ExceptionTranslator.forJson(),
                context
        );

        CharacterEquipment entity = repository.findById(ocid)
                .orElseGet(() -> CharacterEquipment.builder().ocid(ocid).build());

        entity.updateData(json);
        repository.saveAndFlush(entity); // 즉시 물리적 저장 보장
    }
}