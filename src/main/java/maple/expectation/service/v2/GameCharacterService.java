package maple.expectation.service.v2;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterBasicResponse;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캐릭터 도메인 서비스
 *
 * <p>책임:
 *
 * <ul>
 *   <li>캐릭터 조회/생성
 *   <li>Negative/Positive 캐싱
 *   <li>좋아요 버퍼 동기화 지원 (getCharacterForUpdate)
 * </ul>
 *
 * <p>Note: 좋아요 API는 CharacterLikeService로 이관됨 (Self-Like/중복 방지 포함)
 */
@Slf4j
@Service
public class GameCharacterService {

  /** Issue #284 P0: 외부 API 호출 타임아웃 (초) */
  private static final long API_TIMEOUT_SECONDS = 10L;

  private final GameCharacterRepository gameCharacterRepository;
  private final NexonApiClient nexonApiClient;
  private final CacheManager cacheManager;
  private final LogicExecutor executor;
  private final CharacterCreationService characterCreationService;
  private final ObjectProvider<GameCharacterService> selfProvider;

  public GameCharacterService(
      GameCharacterRepository gameCharacterRepository,
      NexonApiClient nexonApiClient,
      CacheManager cacheManager,
      LogicExecutor executor,
      CharacterCreationService characterCreationService,
      ObjectProvider<GameCharacterService> selfProvider) {
    this.gameCharacterRepository = gameCharacterRepository;
    this.nexonApiClient = nexonApiClient;
    this.cacheManager = cacheManager;
    this.executor = executor;
    this.characterCreationService = characterCreationService;
    this.selfProvider = selfProvider;
  }

  /** ⚡ [Negative Cache 확인] executeOrDefault를 사용하여 캐시 존재 여부 및 타입 캐스팅 노이즈 제거 */
  public boolean isNonExistent(String userIgn) {
    String cleanIgn = userIgn.trim();
    return executor.executeOrDefault(
        () -> {
          Cache cache = cacheManager.getCache("ocidNegativeCache");
          return cache != null && "NOT_FOUND".equals(cache.get(cleanIgn, String.class));
        },
        false,
        TaskContext.of("Cache", "CheckNegative", cleanIgn));
  }

  /** ⚡ [N+1 해결] 캐릭터와 장비를 한방에 가져옵니다. */
  public Optional<GameCharacter> getCharacterIfExist(String userIgn) {
    String cleanIgn = userIgn.trim();
    return executor.execute(
        () -> gameCharacterRepository.findByUserIgnWithEquipment(cleanIgn),
        TaskContext.of("DB", "FindWithEquipment", cleanIgn));
  }

  /**
   * 캐릭터 생성 (CharacterCreationService 위임 + 기본 정보 보강)
   *
   * <h4>Issue #226: Connection Pool 고갈 방지</h4>
   *
   * <p>CharacterCreationService에서 트랜잭션 경계 분리 적용
   *
   * @see CharacterCreationService#createNewCharacter(String)
   */
  @ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
  public GameCharacter createNewCharacter(String userIgn) {
    GameCharacter created = characterCreationService.createNewCharacter(userIgn.trim());
    return enrichCharacterBasicInfo(created);
  }

  @Transactional
  public String saveCharacter(GameCharacter character) {
    return executor.execute(
        () -> gameCharacterRepository.save(character).getUserIgn(),
        TaskContext.of("DB", "SaveCharacter", character.getUserIgn()));
  }

  public GameCharacter getCharacterOrThrow(String userIgn) {
    return executor.execute(
        () ->
            gameCharacterRepository
                .findByUserIgnWithEquipment(userIgn)
                .orElseThrow(() -> new CharacterNotFoundException(userIgn)),
        TaskContext.of("DB", "GetOrThrow", userIgn));
  }

  /**
   * 캐릭터 기본 정보 보강 (TieredCache L1/L2 + 15분 간격 갱신)
   *
   * <p>expectation-sequence-diagram 패턴 적용:
   *
   * <ul>
   *   <li>캐시 조회 (L1 → L2): TieredCache Single-flight 패턴
   *   <li>캐시 MISS 시 API 호출 → L2 → L1 저장
   *   <li>비동기 DB 저장: API 응답 후 Background로 DB 업데이트
   * </ul>
   *
   * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신
   *
   * @param character 기본 정보가 없거나 오래된 캐릭터 엔티티
   * @return 기본 정보가 보강된 캐릭터 (또는 원본 그대로)
   */
  public GameCharacter enrichCharacterBasicInfo(GameCharacter character) {
    // DB에 이미 있고 15분 미경과 시 그대로 반환 (DB 우선)
    if (!character.needsBasicInfoRefresh()) {
      return character;
    }

    TaskContext context = TaskContext.of("Character", "EnrichBasicInfo", character.getUserIgn());

    return executor.executeOrDefault(
        () -> fetchAndUpdateBasicInfo(character),
        character, // 실패 시 원본 반환
        context);
  }

  /**
   * TieredCache를 통한 기본 정보 조회 및 엔티티 업데이트
   *
   * <p>cache-sequence.md 패턴: L1 → L2 → API 호출 순서
   */
  private GameCharacter fetchAndUpdateBasicInfo(GameCharacter character) {
    String ocid = character.getOcid();
    Cache cache = cacheManager.getCache("characterBasic");

    // TieredCache: L1 → L2 → API 호출 (Single-flight 패턴)
    CharacterBasicResponse basicInfo =
        cache.get(
            ocid,
            () -> {
              log.info("🔄 [Enrich] 캐릭터 기본 정보 API 호출: {} (캐시 MISS)", character.getUserIgn());
              return nexonApiClient
                  .getCharacterBasic(ocid)
                  .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                  .join();
            });

    // 엔티티 업데이트 (메모리)
    updateCharacterWithBasicInfo(character, basicInfo);

    // 비동기 DB 저장 (Background) — selfProvider로 프록시 경유하여 @Async 활성화
    selfProvider.getObject().saveCharacterBasicInfoAsync(character);

    return character;
  }

  /** 엔티티에 기본 정보 설정 */
  private void updateCharacterWithBasicInfo(
      GameCharacter character, CharacterBasicResponse basicInfo) {
    character.setWorldName(basicInfo.getWorldName());
    character.setCharacterClass(basicInfo.getCharacterClass());
    character.setCharacterImage(basicInfo.getCharacterImage());
    character.setBasicInfoUpdatedAt(java.time.LocalDateTime.now());
  }

  /**
   * 캐릭터 기본 정보 비동기 저장 (DB + 캐시 갱신)
   *
   * <p>expectation-sequence-diagram Phase 7: 비동기 DB 저장 (Background)
   */
  @org.springframework.scheduling.annotation.Async
  @Transactional
  public void saveCharacterBasicInfoAsync(GameCharacter character) {
    executor.executeVoid(
        () -> {
          // DB 저장
          gameCharacterRepository.save(character);
          log.info("✅ [Async] 캐릭터 기본 정보 DB 저장 완료: {}", character.getUserIgn());
        },
        TaskContext.of("DB", "SaveBasicInfoAsync", character.getUserIgn()));
  }

  /** 좋아요 버퍼 동기화용 Pessimistic Lock 조회 LikeSyncExecutor에서 호출하여 likeCount 업데이트에 사용 */
  @Transactional
  @ObservedTransaction("service.v2.GameCharacterService.getCharacterForUpdate")
  public GameCharacter getCharacterForUpdate(String userIgn) {
    return executor.execute(
        () ->
            gameCharacterRepository
                .findByUserIgnWithPessimisticLock(userIgn)
                .orElseThrow(() -> new CharacterNotFoundException(userIgn)),
        TaskContext.of("DB", "GetForUpdate", userIgn));
  }
}
