package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.aop.annotation.LogExecutionTime;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.external.NexonApiClient;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.impl.DatabaseLikeProcessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@TraceLog
@Transactional(readOnly = true)
public class GameCharacterService {

    private final GameCharacterRepository gameCharacterRepository;
    private final NexonApiClient nexonApiClient;
    private final LikeProcessor likeProcessor;
    private final DatabaseLikeProcessor databaseLikeProcessor;

    public GameCharacterService(
            GameCharacterRepository gameCharacterRepository,
            NexonApiClient nexonApiClient,
            LikeProcessor likeProcessor,
            @Lazy DatabaseLikeProcessor databaseLikeProcessor) {
        this.gameCharacterRepository = gameCharacterRepository;
        this.nexonApiClient = nexonApiClient;
        this.likeProcessor = likeProcessor;
        this.databaseLikeProcessor = databaseLikeProcessor;
    }

    @Transactional
    public String saveCharacter(GameCharacter character) {
        return gameCharacterRepository.save(character).getUserIgn();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Locked(key = "#userIgn") // userIgn 매개변수를 기반으로 락 획득
    public GameCharacter findCharacterByUserIgn(String userIgn) {
        String cleanUserIgn = userIgn.trim();

        // 1차 캐시 확인
        return gameCharacterRepository.findByUserIgn(cleanUserIgn)
                .orElseGet(() -> {
                    log.info("✨ 신규 캐릭터 생성 시도: {}", cleanUserIgn);
                    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).getOcid();
                    GameCharacter newChar = new GameCharacter(cleanUserIgn);
                    newChar.setOcid(ocid);
                    return gameCharacterRepository.saveAndFlush(newChar);
                });
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