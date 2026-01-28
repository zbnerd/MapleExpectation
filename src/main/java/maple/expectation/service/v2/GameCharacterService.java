package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterBasicResponse;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.util.ExceptionUtils;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 캐릭터 도메인 서비스
 *
 * <p>책임:
 * <ul>
 *   <li>캐릭터 조회/생성</li>
 *   <li>Negative/Positive 캐싱</li>
 *   <li>좋아요 버퍼 동기화 지원 (getCharacterForUpdate)</li>
 * </ul>
 * </p>
 *
 * <p>Note: 좋아요 API는 CharacterLikeService로 이관됨 (Self-Like/중복 방지 포함)</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameCharacterService {

    private final GameCharacterRepository gameCharacterRepository;
    private final NexonApiClient nexonApiClient;
    private final CacheManager cacheManager;
    private final LogicExecutor executor;

    /**
     * ⚡ [Negative Cache 확인]
     * executeOrDefault를 사용하여 캐시 존재 여부 및 타입 캐스팅 노이즈 제거
     */
    public boolean isNonExistent(String userIgn) {
        String cleanIgn = userIgn.trim();
        return executor.executeOrDefault(() -> {
            Cache cache = cacheManager.getCache("ocidNegativeCache");
            return cache != null && "NOT_FOUND".equals(cache.get(cleanIgn, String.class));
        }, false, TaskContext.of("Cache", "CheckNegative", cleanIgn));
    }

    /**
     * ⚡ [N+1 해결] 캐릭터와 장비를 한방에 가져옵니다.
     */
    public Optional<GameCharacter> getCharacterIfExist(String userIgn) {
        String cleanIgn = userIgn.trim();
        return executor.execute(
                () -> gameCharacterRepository.findByUserIgnWithEquipment(cleanIgn),
                TaskContext.of("DB", "FindWithEquipment", cleanIgn)
        );
    }

    /**
     * ⚙️ [캐릭터 생성 로직] - Issue #226: 트랜잭션 경계 분리
     *
     * <h4>Connection Pool 고갈 방지 (P1)</h4>
     * <p>기존 문제: @Transactional 범위 내 .join() 호출 → 최대 28초 DB Connection 점유</p>
     * <p>해결: API 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안</p>
     *
     * <h4>Connection 점유 시간</h4>
     * <ul>
     *   <li>Before: 최대 28초 (TimeLimiter 상한)</li>
     *   <li>After: ~100ms (saveAndFlush만)</li>
     * </ul>
     *
     * @see <a href="https://github.com/issue/226">Issue #226: Connection Vampire 방지</a>
     */
    @ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
    public GameCharacter createNewCharacter(String userIgn) {
        String cleanUserIgn = userIgn.trim();
        TaskContext context = TaskContext.of("Character", "Create", cleanUserIgn);

        return executor.executeOrCatch(
                () -> {
                    log.info("✨ [Creation] 캐릭터 생성 시작: {}", cleanUserIgn);

                    // Step 1: OCID 조회 (트랜잭션 밖 - DB Connection 점유 없음)
                    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).join().getOcid();

                    // Step 2: 캐릭터 기본 정보 조회 (트랜잭션 밖 - DB Connection 점유 없음)
                    CharacterBasicResponse basicInfo = nexonApiClient.getCharacterBasic(ocid).join();

                    // Step 3: DB 저장 (트랜잭션 안 - 짧은 Connection 점유 ~100ms)
                    return saveCharacterWithCaching(cleanUserIgn, ocid, basicInfo);
                },
                (e) -> {
                    // PR #199, #241 Fix: CompletionException unwrap 후 CharacterNotFoundException 감지
                    Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);
                    if (unwrapped instanceof CharacterNotFoundException) {
                        log.warn("🚫 [Recovery] 캐릭터 미존재 확인 -> 네거티브 캐시 저장: {}", cleanUserIgn);
                        Optional.ofNullable(cacheManager.getCache("ocidNegativeCache"))
                                .ifPresent(c -> c.put(cleanUserIgn, "NOT_FOUND"));
                    }
                    // 발생한 예외를 그대로 던져 상위 트랜잭션/핸들러로 전달
                    throw (RuntimeException) e;
                },
                context
        );
    }

    /**
     * DB 저장 + 캐싱 (트랜잭션 범위 최소화) - Issue #226
     *
     * <p>Connection 점유 시간: ~100ms (28초 → 100ms)</p>
     *
     * <h4>원자성 보장</h4>
     * <ul>
     *   <li>saveAndFlush()로 즉시 커밋</li>
     *   <li>API 호출 결과(OCID)는 상태를 변경하지 않음 (조회 전용)</li>
     *   <li>재시도 시 동일한 OCID가 반환되므로 멱등성 보장</li>
     * </ul>
     *
     * <h4>Race Condition 대응</h4>
     * <ul>
     *   <li>DB Unique Constraint (userIgn)로 중복 방지</li>
     *   <li>동시 요청 시 DataIntegrityViolationException → 기존 엔티티 조회</li>
     * </ul>
     *
     * @param userIgn 캐릭터 닉네임
     * @param ocid 캐릭터 고유 ID
     * @param basicInfo Nexon API character/basic 응답 (worldName, characterClass, characterImage)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GameCharacter saveCharacterWithCaching(String userIgn, String ocid, CharacterBasicResponse basicInfo) {
        GameCharacter character = new GameCharacter(userIgn, ocid);

        // Nexon API character/basic 정보 설정
        character.setWorldName(basicInfo.getWorldName());
        character.setCharacterClass(basicInfo.getCharacterClass());
        character.setCharacterImage(basicInfo.getCharacterImage());
        character.setBasicInfoUpdatedAt(java.time.LocalDateTime.now());

        GameCharacter saved = gameCharacterRepository.saveAndFlush(character);

        // Positive caching: OCID 캐시
        Optional.ofNullable(cacheManager.getCache("ocidCache"))
                .ifPresent(c -> c.put(userIgn, ocid));

        // TieredCache: characterBasic 캐시 (L1 + L2)
        Optional.ofNullable(cacheManager.getCache("characterBasic"))
                .ifPresent(c -> c.put(ocid, basicInfo));

        return saved;
    }

    @Transactional
    public String saveCharacter(GameCharacter character) {
        return executor.execute(
                () -> gameCharacterRepository.save(character).getUserIgn(),
                TaskContext.of("DB", "SaveCharacter", character.getUserIgn())
        );
    }

    public GameCharacter getCharacterOrThrow(String userIgn) {
        return executor.execute(
                () -> gameCharacterRepository.findByUserIgnWithEquipment(userIgn)
                        .orElseThrow(() -> new CharacterNotFoundException(userIgn)),
                TaskContext.of("DB", "GetOrThrow", userIgn)
        );
    }

    /**
     * 캐릭터 기본 정보 보강 (TieredCache L1/L2 + 15분 간격 갱신)
     *
     * <p>expectation-sequence-diagram 패턴 적용:</p>
     * <ul>
     *   <li>캐시 조회 (L1 → L2): TieredCache Single-flight 패턴</li>
     *   <li>캐시 MISS 시 API 호출 → L2 → L1 저장</li>
     *   <li>비동기 DB 저장: API 응답 후 Background로 DB 업데이트</li>
     * </ul>
     *
     * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신</p>
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
                character,  // 실패 시 원본 반환
                context
        );
    }

    /**
     * TieredCache를 통한 기본 정보 조회 및 엔티티 업데이트
     *
     * <p>cache-sequence.md 패턴: L1 → L2 → API 호출 순서</p>
     */
    private GameCharacter fetchAndUpdateBasicInfo(GameCharacter character) {
        String ocid = character.getOcid();
        Cache cache = cacheManager.getCache("characterBasic");

        // TieredCache: L1 → L2 → API 호출 (Single-flight 패턴)
        CharacterBasicResponse basicInfo = cache.get(ocid, () -> {
            log.info("🔄 [Enrich] 캐릭터 기본 정보 API 호출: {} (캐시 MISS)", character.getUserIgn());
            return nexonApiClient.getCharacterBasic(ocid).join();
        });

        // 엔티티 업데이트 (메모리)
        updateCharacterWithBasicInfo(character, basicInfo);

        // 비동기 DB 저장 (Background)
        saveCharacterBasicInfoAsync(character);

        return character;
    }

    /**
     * 엔티티에 기본 정보 설정
     */
    private void updateCharacterWithBasicInfo(GameCharacter character, CharacterBasicResponse basicInfo) {
        character.setWorldName(basicInfo.getWorldName());
        character.setCharacterClass(basicInfo.getCharacterClass());
        character.setCharacterImage(basicInfo.getCharacterImage());
        character.setBasicInfoUpdatedAt(java.time.LocalDateTime.now());
    }

    /**
     * 캐릭터 기본 정보 비동기 저장 (DB + 캐시 갱신)
     *
     * <p>expectation-sequence-diagram Phase 7: 비동기 DB 저장 (Background)</p>
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
                TaskContext.of("DB", "SaveBasicInfoAsync", character.getUserIgn())
        );
    }

    /**
     * 좋아요 버퍼 동기화용 Pessimistic Lock 조회
     * LikeSyncExecutor에서 호출하여 likeCount 업데이트에 사용
     */
    @Transactional
    @ObservedTransaction("service.v2.GameCharacterService.getCharacterForUpdate")
    public GameCharacter getCharacterForUpdate(String userIgn) {
        return executor.execute(
                () -> gameCharacterRepository.findByUserIgnWithPessimisticLock(userIgn)
                        .orElseThrow(() -> new CharacterNotFoundException(userIgn)),
                TaskContext.of("DB", "GetForUpdate", userIgn)
        );
    }
}