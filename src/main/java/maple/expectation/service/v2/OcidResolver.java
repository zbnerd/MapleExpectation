package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * OCID 조회 전담 컴포넌트 (Get or Create 패턴)
 *
 * <p>역할:
 * <ul>
 *   <li>userIgn → OCID 변환</li>
 *   <li>DB 조회 → 있으면 반환</li>
 *   <li>DB 없으면 → NexonAPI 호출 → DB 저장 → 반환</li>
 * </ul>
 * </p>
 *
 * <p>SRP 원칙: OCID 조회/생성 책임만 담당하여 순환 의존성 방지</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcidResolver {

    private final GameCharacterRepository gameCharacterRepository;
    private final NexonApiClient nexonApiClient;
    private final CacheManager cacheManager;
    private final LogicExecutor executor;

    /**
     * userIgn으로 OCID 조회 (Get or Create)
     *
     * <p>흐름:
     * <ol>
     *   <li>Negative Cache 확인 → 존재하면 CharacterNotFoundException</li>
     *   <li>DB 조회 → 있으면 OCID 반환</li>
     *   <li>없으면 → NexonAPI 호출 → DB 저장 → OCID 반환</li>
     * </ol>
     * </p>
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

        // 2. DB 조회 → 있으면 반환
        Optional<GameCharacter> existing = executor.execute(
                () -> gameCharacterRepository.findByUserIgn(cleanIgn),
                TaskContext.of("Ocid", "DbLookup", cleanIgn)
        );

        if (existing.isPresent()) {
            return existing.get().getOcid();
        }

        // 3. 없으면 → NexonAPI 호출 → DB 저장 → 반환
        return createAndGetOcid(cleanIgn);
    }

    /**
     * userIgn으로 GameCharacter 조회 (Get or Create)
     */
    public GameCharacter resolveCharacter(String userIgn) {
        String cleanIgn = userIgn.trim();

        // 1. Negative Cache 확인
        if (isNonExistent(cleanIgn)) {
            throw new CharacterNotFoundException(cleanIgn);
        }

        // 2. DB 조회 → 있으면 반환
        Optional<GameCharacter> existing = executor.execute(
                () -> gameCharacterRepository.findByUserIgn(cleanIgn),
                TaskContext.of("Character", "DbLookup", cleanIgn)
        );

        // 3. 없으면 → 생성 후 반환

        return existing.orElseGet(() -> createNewCharacter(cleanIgn));


    }

    /**
     * NexonAPI로 OCID 조회 → DB 저장 → OCID 반환
     */
    private String createAndGetOcid(String userIgn) {
        return createNewCharacter(userIgn).getOcid();
    }

    /**
     * 캐릭터 생성 (NexonAPI 호출 → DB 저장)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GameCharacter createNewCharacter(String userIgn) {
        TaskContext context = TaskContext.of("Character", "Create", userIgn);

        return executor.executeOrCatch(
                () -> {
                    log.info("✨ [Creation] 캐릭터 생성 시작: {}", userIgn);
                    String ocid = nexonApiClient.getOcidByCharacterName(userIgn).getOcid();

                    GameCharacter saved = gameCharacterRepository.saveAndFlush(
                            new GameCharacter(userIgn, ocid)
                    );

                    // Positive Cache
                    Optional.ofNullable(cacheManager.getCache("ocidCache"))
                            .ifPresent(c -> c.put(userIgn, ocid));

                    return saved;
                },
                e -> {
                    // CharacterNotFoundException → Negative Cache 저장
                    if (e instanceof CharacterNotFoundException) {
                        log.warn("🚫 [Recovery] 캐릭터 미존재 → 네거티브 캐시 저장: {}", userIgn);
                        Optional.ofNullable(cacheManager.getCache("ocidNegativeCache"))
                                .ifPresent(c -> c.put(userIgn, "NOT_FOUND"));
                    }
                    throw (RuntimeException) e;
                },
                context
        );
    }

    /**
     * Negative Cache 확인
     */
    private boolean isNonExistent(String userIgn) {
        return executor.executeOrDefault(() -> {
            Cache cache = cacheManager.getCache("ocidNegativeCache");
            return cache != null && "NOT_FOUND".equals(cache.get(userIgn, String.class));
        }, false, TaskContext.of("Cache", "CheckNegative", userIgn));
    }
}
