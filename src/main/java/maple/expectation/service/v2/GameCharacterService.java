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
     */
    public boolean isNonExistent(String userIgn) {
        Cache cache = cacheManager.getCache("ocidNegativeCache");
        if (cache == null) return false;
        Object val = cache.get(userIgn.trim(), Object.class);
        return "NOT_FOUND".equals(val);
    }

    /**
     * ⚡ [N+1 해결] JOIN FETCH를 사용하여 캐릭터와 장비를 한방에 가져옵니다.
     */
    public Optional<GameCharacter> getCharacterIfExist(String userIgn) {
        return gameCharacterRepository.findByUserIgnWithEquipment(userIgn.trim());
    }

    /**
     * ⚙️ [실제 생성 로직]
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
    public GameCharacter createNewCharacter(String userIgn) {
        String cleanUserIgn = userIgn.trim();
        try {
            log.info("✨ [Creation Process] 캐릭터 생성 시작: {}", cleanUserIgn);
            String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).getOcid();

            GameCharacter saved = gameCharacterRepository.saveAndFlush(new GameCharacter(cleanUserIgn, ocid));

            Optional.ofNullable(cacheManager.getCache("ocidCache"))
                    .ifPresent(c -> c.put(cleanUserIgn, ocid));
            return saved;
        } catch (CharacterNotFoundException e) {
            log.warn("🚫 [Negative Cache Saved] 캐릭터 진짜 없음 확인: {}", cleanUserIgn);
            Optional.ofNullable(cacheManager.getCache("ocidNegativeCache"))
                    .ifPresent(c -> c.put(cleanUserIgn, "NOT_FOUND"));
            throw e;
        } catch (Exception e) {
            log.error("⚠️ [Temporary Failure] 넥슨 API 통신 실패: {}", cleanUserIgn);
            throw e;
        }
    }

    @Transactional
    public String saveCharacter(GameCharacter character) {
        return gameCharacterRepository.save(character).getUserIgn();
    }

    /**
     * 상세 조회 (기존 로직 유지)
     */
    public GameCharacter getCharacterOrThrow(String userIgn) {
        return gameCharacterRepository.findByUserIgnWithEquipment(userIgn)
                .orElseThrow(() -> new CharacterNotFoundException(userIgn));
    }

    // --- 🚀 ObservedTransaction 복구 영역 ---

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

    @Transactional
    @ObservedTransaction("service.v2.GameCharacterService.getCharacterForUpdate")
    public GameCharacter getCharacterForUpdate(String userIgn) {
        return gameCharacterRepository.findByUserIgnWithPessimisticLock(userIgn)
                .orElseThrow(() -> new CharacterNotFoundException(userIgn));
    }
}