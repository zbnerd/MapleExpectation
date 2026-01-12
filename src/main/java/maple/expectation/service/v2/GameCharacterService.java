package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
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
     * ⚙️ [캐릭터 생성 로직]
     * try-catch를 박멸하고 executeWithRecovery를 통해 예외 복구 로직(Negative Caching) 통합
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
    public GameCharacter createNewCharacter(String userIgn) {
        String cleanUserIgn = userIgn.trim();
        TaskContext context = TaskContext.of("Character", "Create", cleanUserIgn);

        // ✅ [패턴 5] executeWithRecovery: 정상 로직 실행 후 특정 예외 발생 시 복구(사후 처리) 로직 가동
        return executor.executeOrCatch(
                () -> {
                    log.info("✨ [Creation] 캐릭터 생성 시작: {}", cleanUserIgn);
                    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).getOcid();

                    GameCharacter saved = gameCharacterRepository.saveAndFlush(new GameCharacter(cleanUserIgn, ocid));

                    // 포지티브 캐싱
                    Optional.ofNullable(cacheManager.getCache("ocidCache"))
                            .ifPresent(c -> c.put(cleanUserIgn, ocid));
                    return saved;
                },
                (e) -> {
                    // CharacterNotFoundException 발생 시에만 네거티브 캐싱 수행 후 예외 재전파
                    if (e instanceof CharacterNotFoundException) {
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