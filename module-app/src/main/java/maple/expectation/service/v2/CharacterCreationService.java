package maple.expectation.service.v2;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.global.error.exception.ApiTimeoutException;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.error.exception.base.BaseException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.util.ExceptionUtils;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캐릭터 생성 공통 서비스 (OcidResolver + GameCharacterService 중복 제거)
 *
 * <h3>책임: 캐릭터 생성 + 네거티브/포지티브 캐싱</h3>
 *
 * <ul>
 *   <li>Nexon API OCID 조회
 *   <li>DB 저장 (트랜잭션 범위 최소화)
 *   <li>네거티브 캐시 (CharacterNotFoundException 시)
 *   <li>포지티브 캐시 (OCID 캐시)
 * </ul>
 *
 * <h3>분해 근거</h3>
 *
 * <p>OcidResolver.createNewCharacter()와 GameCharacterService.createNewCharacter()의 공통 로직 추출
 * (CLAUDE.md Section 4 - SOLID SRP)
 *
 * @see OcidResolver
 * @see GameCharacterService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterCreationService {

  /** Issue #284 P0: 외부 API 호출 타임아웃 (초) */
  private static final long API_TIMEOUT_SECONDS = 10L;

  private final GameCharacterRepository gameCharacterRepository;
  private final NexonApiClient nexonApiClient;
  private final CacheManager cacheManager;
  private final LogicExecutor executor;

  /**
   * 캐릭터 생성 - Issue #226: 트랜잭션 경계 분리
   *
   * <h4>Connection Pool 고갈 방지 (P1)</h4>
   *
   * <p>API 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안
   *
   * @param userIgn 캐릭터 닉네임
   * @return 생성된 GameCharacter
   * @throws CharacterNotFoundException Nexon API에서 캐릭터가 존재하지 않는 경우
   */
  public GameCharacter createNewCharacter(String userIgn) {
    TaskContext context = TaskContext.of("Character", "Create", userIgn);

    return executor.executeOrCatch(
        () -> {
          log.info("✨ [Creation] 캐릭터 생성 시작: {}", userIgn);

          // Step 1: OCID 조회 (트랜잭션 밖 - DB Connection 점유 없음)
          // Issue #284 P0: 10초 타임아웃으로 무기한 대기 방지
          String ocid =
              nexonApiClient
                  .getOcidByCharacterName(userIgn)
                  .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                  .join()
                  .getOcid();

          // Step 2: DB 저장 (트랜잭션 안 - 짧은 Connection 점유 ~100ms)
          return saveCharacterWithCaching(userIgn, ocid);
        },
        e -> handleCreationFailure(userIgn, e),
        context);
  }

  /**
   * DB 저장 + 포지티브 캐싱 (트랜잭션 범위 최소화) - Issue #226
   *
   * <p>Connection 점유 시간: ~100ms (28초 → 100ms)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public GameCharacter saveCharacterWithCaching(String userIgn, String ocid) {
    GameCharacter saved = gameCharacterRepository.saveAndFlush(new GameCharacter(userIgn, ocid));

    // Positive Cache: OCID 캐시
    Optional.ofNullable(cacheManager.getCache("ocidCache")).ifPresent(c -> c.put(userIgn, ocid));

    return saved;
  }

  /**
   * 캐릭터 생성 실패 처리 (네거티브 캐시 + 예외 재전파)
   *
   * <p>PR #199, #241 Fix: CompletionException unwrap 후 CharacterNotFoundException 감지
   */
  private GameCharacter handleCreationFailure(String userIgn, Throwable e) {
    Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);

    // Issue #284 P0: TimeoutException 감지 → 서킷브레이커 기록
    if (unwrapped instanceof TimeoutException) {
      throw new ApiTimeoutException("NexonOcidAPI", unwrapped);
    }

    if (unwrapped instanceof CharacterNotFoundException) {
      log.warn("🚫 [Recovery] 캐릭터 미존재 → 네거티브 캐시 저장: {}", userIgn);
      Optional.ofNullable(cacheManager.getCache("ocidNegativeCache"))
          .ifPresent(c -> c.put(userIgn, "NOT_FOUND"));
    }
    if (e instanceof BaseException be) {
      throw be;
    }
    if (e instanceof RuntimeException re) {
      throw re;
    }
    throw new InternalSystemException("CharacterCreationService.createNewCharacter", e);
  }
}
