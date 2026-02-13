package maple.expectation.service.v2;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * OCID 조회 전담 컴포넌트 (Get or Create 패턴)
 *
 * <p>역할:
 *
 * <ul>
 *   <li>userIgn → OCID 변환
 *   <li>DB 조회 → 있으면 반환
 *   <li>DB 없으면 → NexonAPI 호출 → DB 저장 → 반환
 * </ul>
 *
 * <p>SRP 원칙: OCID 조회/생성 책임만 담당하여 순환 의존성 방지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcidResolver {

  private final GameCharacterRepository gameCharacterRepository;
  private final CharacterCreationService characterCreationService;
  private final CacheManager cacheManager;
  private final LogicExecutor executor;

  /**
   * userIgn으로 OCID 조회 (Get or Create)
   *
   * <p>흐름:
   *
   * <ol>
   *   <li>Negative Cache 확인 → 존재하면 CharacterNotFoundException
   *   <li>DB 조회 → 있으면 OCID 반환
   *   <li>없으면 → NexonAPI 호출 → DB 저장 → OCID 반환
   * </ol>
   *
   * @param userIgn 캐릭터 닉네임
   * @return OCID
   * @throws CharacterNotFoundException 넥슨 API에서도 캐릭터가 존재하지 않는 경우
   */
  public String resolve(String userIgn) {
    String cleanIgn = userIgn.trim();

    // 1. Negative Cache 확인
    if (isNonExistent(cleanIgn)) {
      throw new CharacterNotFoundException(cleanIgn);
    }

    // 2. Positive Cache 확인 (P1-8 Fix: DB RTT 절약)
    String cached = getCachedOcid(cleanIgn);
    if (cached != null) {
      return cached;
    }

    // 3. DB 조회 → 있으면 반환, 없으면 → NexonAPI 호출 → DB 저장 → 반환
    // P1-1 Fix: CLAUDE.md Section 4 - Optional Chaining Best Practice
    return executor
        .execute(
            () -> gameCharacterRepository.findByUserIgn(cleanIgn),
            TaskContext.of("Ocid", "DbLookup", cleanIgn))
        .map(gc -> cacheAndReturn(cleanIgn, gc.getOcid()))
        .orElseGet(() -> createAndGetOcid(cleanIgn));
  }

  /** userIgn으로 GameCharacter 조회 (Get or Create) */
  public GameCharacter resolveCharacter(String userIgn) {
    String cleanIgn = userIgn.trim();

    // 1. Negative Cache 확인
    if (isNonExistent(cleanIgn)) {
      throw new CharacterNotFoundException(cleanIgn);
    }

    // 2. DB 조회 → 있으면 반환, 없으면 → 생성 후 반환
    // P1-2 Fix: CLAUDE.md Section 4 - Optional Chaining Best Practice (간결화)
    return executor
        .execute(
            () -> gameCharacterRepository.findByUserIgn(cleanIgn),
            TaskContext.of("Character", "DbLookup", cleanIgn))
        .orElseGet(() -> createNewCharacter(cleanIgn));
  }

  /** NexonAPI로 OCID 조회 → DB 저장 → OCID 반환 */
  private String createAndGetOcid(String userIgn) {
    return createNewCharacter(userIgn).getOcid();
  }

  /**
   * 캐릭터 생성 (CharacterCreationService 위임)
   *
   * @see CharacterCreationService#createNewCharacter(String)
   */
  public GameCharacter createNewCharacter(String userIgn) {
    return characterCreationService.createNewCharacter(userIgn);
  }

  /**
   * Positive Cache 조회 (P1-8: DB RTT 절약)
   *
   * @return 캐싱된 OCID, 없으면 null
   */
  private String getCachedOcid(String userIgn) {
    return executor.executeOrDefault(
        () -> {
          Cache cache = cacheManager.getCache("ocidCache");
          return cache != null ? cache.get(userIgn, String.class) : null;
        },
        null,
        TaskContext.of("Cache", "CheckPositive", userIgn));
  }

  /** DB 조회 결과를 캐시에 저장 후 반환 (Tap 패턴) */
  private String cacheAndReturn(String userIgn, String ocid) {
    Optional.ofNullable(cacheManager.getCache("ocidCache")).ifPresent(c -> c.put(userIgn, ocid));
    return ocid;
  }

  /** Negative Cache 확인 */
  private boolean isNonExistent(String userIgn) {
    return executor.executeOrDefault(
        () -> {
          Cache cache = cacheManager.getCache("ocidNegativeCache");
          return cache != null && "NOT_FOUND".equals(cache.get(userIgn, String.class));
        },
        false,
        TaskContext.of("Cache", "CheckNegative", userIgn));
  }
}
