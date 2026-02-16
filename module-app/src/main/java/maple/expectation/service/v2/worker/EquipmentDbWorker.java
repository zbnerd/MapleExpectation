package maple.expectation.service.v2.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.repository.CharacterEquipmentRepository;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.shutdown.PersistenceTrackerStrategy;
import maple.expectation.util.StringMaskingUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipment DB 계층 전담 Worker (SRP 준수)
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>DB 조회: 15분 TTL 체크 포함
 *   <li>DB 저장: 비동기 + Graceful Shutdown 지원
 * </ul>
 *
 * <h4>데이터 소스 계층 (L1 → L2 → DB → API)</h4>
 *
 * <p>DB는 L2 캐시 뒤, Nexon API 앞에 위치하여 API 호출 최소화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDbWorker {

  /** DB 데이터 유효 기간 (Issue #120 Rich Domain) */
  private static final Duration DB_TTL = Duration.ofMinutes(15);

  private final CharacterEquipmentRepository repository;
  private final ObjectMapper objectMapper;
  private final PersistenceTrackerStrategy persistenceTracker;
  private final LogicExecutor executor;

  /** ✅ 비동기 저장 로직 평탄화 try-catch 대신 executeWithRecovery를 사용하여 Future의 상태를 결정합니다. */
  @Async
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CompletableFuture<Void> persist(String ocid, EquipmentResponse response) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    TaskContext context = TaskContext.of("EquipmentWorker", "AsyncPersist", ocid); //

    // 1. Graceful Shutdown 지원: 작업 추적 등록
    persistenceTracker.trackOperation(ocid, future);

    // ✅ [패턴 5] executeWithRecovery: 성공 시 complete, 실패 시 completeExceptionally 수행
    return executor.executeOrCatch(
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
        context);
  }

  /** 헬퍼: 실제 저장 로직 (직렬화 및 DB 반영) */
  private void performSave(String ocid, EquipmentResponse response, TaskContext context) {
    //  Jackson 직렬화 시 발생하는 체크 예외를 도메인 예외로 세탁
    String json =
        executor.executeWithTranslation(
            () -> objectMapper.writeValueAsString(response),
            ExceptionTranslator.forJson(),
            context);

    CharacterEquipment entity =
        repository
            .findById(maple.expectation.domain.model.character.CharacterId.of(ocid))
            .orElseGet(
                () ->
                    CharacterEquipment.createEmpty(
                        maple.expectation.domain.model.character.CharacterId.of(ocid)));

    CharacterEquipment updated =
        entity.withUpdatedData(maple.expectation.domain.model.equipment.EquipmentData.of(json));
    repository.save(updated); // 즉시 물리적 저장 보장
  }

  // ==================== DB 조회 API (SRP: DB 계층 전담) ====================

  /**
   * 유효한 DB 데이터 조회 (Rich Domain Model)
   *
   * <p>Issue #120: CharacterEquipment.isFresh(Duration)를 사용하여 TTL 체크
   *
   * @param ocid 캐릭터 OCID
   * @return 유효한 JSON 데이터 (없거나 만료되면 empty)
   */
  @Transactional(readOnly = true)
  public Optional<String> findValidJson(String ocid) {
    return executor.execute(
        () -> {
          Optional<CharacterEquipment> result =
              repository
                  .findById(maple.expectation.domain.model.character.CharacterId.of(ocid))
                  .filter(equipment -> equipment.isFresh(DB_TTL)); // Rich Domain

          if (result.isPresent()) {
            log.debug(
                "[EquipmentDb] DB HIT (TTL valid): ocid={}", StringMaskingUtils.maskOcid(ocid));
          } else {
            log.debug(
                "[EquipmentDb] DB MISS or TTL expired: ocid={}", StringMaskingUtils.maskOcid(ocid));
          }

          return result
              .filter(CharacterEquipment::hasData) // Rich Domain
              .map(CharacterEquipment::jsonContent);
        },
        TaskContext.of("EquipmentDb", "FindValid", ocid));
  }

  // ==================== Raw JSON 저장 API (Expectation 경로용) ====================

  /**
   * Raw JSON 비동기 저장 (Expectation 경로 전용)
   *
   * <p>EquipmentResponse 직렬화 없이 이미 직렬화된 JSON을 저장
   *
   * <p>Nexon API 호출 후 DB에 저장하여 다음 요청에서 API 호출 최소화
   *
   * @param ocid 캐릭터 OCID
   * @param json 저장할 JSON 문자열
   * @return 완료 Future
   */
  @Async
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CompletableFuture<Void> persistRawJson(String ocid, String json) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    TaskContext context = TaskContext.of("EquipmentDb", "PersistRaw", ocid);

    persistenceTracker.trackOperation(ocid, future);

    return executor.executeOrCatch(
        () -> {
          performRawSave(ocid, json);
          log.debug("💾 [DB Save] Raw JSON saved: ocid={}", StringMaskingUtils.maskOcid(ocid));
          future.complete(null);
          return future;
        },
        (e) -> {
          log.error(
              "❌ [DB Save Error] ocid={} | err={}",
              StringMaskingUtils.maskOcid(ocid),
              e.getMessage());
          future.completeExceptionally(e);
          return future;
        },
        context);
  }

  /** 헬퍼: Raw JSON 저장 로직 */
  private void performRawSave(String ocid, String json) {
    CharacterEquipment entity =
        repository
            .findById(maple.expectation.domain.model.character.CharacterId.of(ocid))
            .orElseGet(
                () ->
                    CharacterEquipment.createEmpty(
                        maple.expectation.domain.model.character.CharacterId.of(ocid)));

    CharacterEquipment updated = entity.withUpdatedData(json);
    repository.save(updated);
  }
}
