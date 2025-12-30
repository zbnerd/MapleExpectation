package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.LogExecutionTime;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.impl.DatabaseLikeProcessor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameCharacterService {

    private final GameCharacterRepository gameCharacterRepository;
    private final NexonApiClient nexonApiClient;
    private final LikeProcessor likeProcessor;
    private final DatabaseLikeProcessor databaseLikeProcessor;
    private final CacheManager cacheManager;

    /**
     * ⚡ [Negative Cache 확인]
     * 차단 전용 바구니(ocidNegativeCache)에서 해당 캐릭터가 블랙리스트인지 확인합니다.
     */
    public boolean isNonExistent(String userIgn) {
        Cache cache = cacheManager.getCache("ocidNegativeCache");
        if (cache == null) return false;

        // 타입을 Object로 꺼내어 안전하게 비교합니다.
        Object val = cache.get(userIgn.trim(), Object.class);
        return "NOT_FOUND".equals(val);
    }

    /**
     * ⚡ [단순 DB 조회]
     */
    public Optional<GameCharacter> getCharacterIfExist(String userIgn) {
        return gameCharacterRepository.findByUserIgn(userIgn.trim());
    }

    /**
     * ⚙️ [실제 생성 로직] - 워커(Worker)에 의해 호출됨
     * 이제 이 메서드는 여러 스레드에 의해 동시에 난타당하지 않고,
     * 워커가 큐에서 하나씩 꺼내어 호출하므로 매우 안전합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
    public GameCharacter createNewCharacter(String userIgn) {
        String cleanUserIgn = userIgn.trim();

        // 1. 넥슨 API를 통해 OCID 조회
        try {
            log.info("✨ [Creation Process] 캐릭터 생성 시작: {}", cleanUserIgn);
            String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).getOcid();

            // 2. DB 저장
            GameCharacter saved = gameCharacterRepository.saveAndFlush(new GameCharacter(cleanUserIgn, ocid));

            // 3. [Positive Cache] 캐시에는 객체가 아닌 'OCID 문자열'만 저장 (타입 충돌 방지)
            Optional.ofNullable(cacheManager.getCache("ocidCache"))
                    .ifPresent(c -> c.put(cleanUserIgn, ocid));

            return saved;

        } catch (CharacterNotFoundException e) {
            // 🚀 [Negative Cache] 진짜 넥슨에 없는 경우만 블랙리스트 등록
            log.warn("🚫 [Negative Cache Saved] 캐릭터 진짜 없음 확인 (404): {}", cleanUserIgn);
            Optional.ofNullable(cacheManager.getCache("ocidNegativeCache"))
                    .ifPresent(c -> c.put(cleanUserIgn, "NOT_FOUND"));
            throw e;
        } catch (Exception e) {
            // ⚠️ [Transient Error] 429(Rate Limit) 등 일시적 실패는 캐싱하지 않음
            log.error("⚠️ [Temporary Failure] 넥슨 API 통신 실패, 캐싱 건너뜀: {}", cleanUserIgn);
            throw e;
        }
    }

    @Transactional
    public String saveCharacter(GameCharacter character) {
        return gameCharacterRepository.save(character).getUserIgn();
    }

    @LogExecutionTime
    @ObservedTransaction("service.v2.GameCharacterService.clickLikeCache")
    public void clickLikeCache(String userIgn) {
        likeProcessor.processLike(userIgn);
    }

    @LogExecutionTime
    @Transactional
    @ObservedTransaction("service.v2.GameCharacterService.clickLikePessimistic")
    public void clickLikePessimistic(String userIgn) {
        databaseLikeProcessor.processLike(userIgn);
    }

    public GameCharacter getCharacterOrThrow(String userIgn) {
        return gameCharacterRepository.findByUserIgn(userIgn)
                .orElseThrow(() -> new CharacterNotFoundException(userIgn));
    }

    @Transactional
    public GameCharacter getCharacterForUpdate(String userIgn) {
        return gameCharacterRepository.findByUserIgnWithPessimisticLock(userIgn)
                .orElseThrow(() -> new CharacterNotFoundException(userIgn));
    }
}