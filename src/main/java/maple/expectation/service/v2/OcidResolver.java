package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.util.ExceptionUtils;
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

        // 2. DB 조회 → 있으면 반환, 없으면 → NexonAPI 호출 → DB 저장 → 반환
        // P1-1 Fix: CLAUDE.md Section 4 - Optional Chaining Best Practice
        return executor.execute(
                () -> gameCharacterRepository.findByUserIgn(cleanIgn),
                TaskContext.of("Ocid", "DbLookup", cleanIgn)
        )
        .map(GameCharacter::getOcid)
        .orElseGet(() -> createAndGetOcid(cleanIgn));
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

        // 2. DB 조회 → 있으면 반환, 없으면 → 생성 후 반환
        // P1-2 Fix: CLAUDE.md Section 4 - Optional Chaining Best Practice (간결화)
        return executor.execute(
                () -> gameCharacterRepository.findByUserIgn(cleanIgn),
                TaskContext.of("Character", "DbLookup", cleanIgn)
        ).orElseGet(() -> createNewCharacter(cleanIgn));
    }

    /**
     * NexonAPI로 OCID 조회 → DB 저장 → OCID 반환
     */
    private String createAndGetOcid(String userIgn) {
        return createNewCharacter(userIgn).getOcid();
    }

    /**
     * 캐릭터 생성 - Issue #226: 트랜잭션 경계 분리
     *
     * <h4>Connection Pool 고갈 방지 (P1)</h4>
     * <p>기존 문제: @Transactional 범위 내 .join() 호출 → 최대 28초 DB Connection 점유</p>
     * <p>해결: API 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안</p>
     *
     * @see <a href="https://github.com/issue/226">Issue #226: Connection Vampire 방지</a>
     */
    public GameCharacter createNewCharacter(String userIgn) {
        TaskContext context = TaskContext.of("Character", "Create", userIgn);

        return executor.executeOrCatch(
                () -> {
                    log.info("✨ [Creation] 캐릭터 생성 시작: {}", userIgn);

                    // Step 1: API 호출 (트랜잭션 밖 - DB Connection 점유 없음)
                    String ocid = nexonApiClient.getOcidByCharacterName(userIgn).join().getOcid();

                    // Step 2: DB 저장 (트랜잭션 안 - 짧은 Connection 점유 ~100ms)
                    return saveCharacterWithCaching(userIgn, ocid);
                },
                e -> {
                    // PR #199, #241 Fix: CompletionException unwrap 후 CharacterNotFoundException 감지
                    Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);
                    if (unwrapped instanceof CharacterNotFoundException) {
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
     * DB 저장 + 캐싱 (트랜잭션 범위 최소화) - Issue #226
     *
     * <p>Connection 점유 시간: ~100ms (28초 → 100ms)</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GameCharacter saveCharacterWithCaching(String userIgn, String ocid) {
        GameCharacter saved = gameCharacterRepository.saveAndFlush(
                new GameCharacter(userIgn, ocid)
        );

        // Positive Cache
        Optional.ofNullable(cacheManager.getCache("ocidCache"))
                .ifPresent(c -> c.put(userIgn, ocid));

        return saved;
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
