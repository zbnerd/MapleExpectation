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
    @Locked(key = "#userIgn")
    public GameCharacter findCharacterByUserIgn(String userIgn) {
        String cleanUserIgn = userIgn.trim();

        // 1. DB에서 먼저 조회
        return gameCharacterRepository.findByUserIgn(cleanUserIgn)
                .orElseGet(() -> {
                    log.info("✨ 신규 캐릭터 생성 시도: {}", cleanUserIgn);

                    // 2. [변경] 객체 생성 전에 넥슨 API를 호출해서 OCID를 먼저 확보합니다.
                    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).getOcid();

                    // 3. [수정] 이제 '완전한 상태'로 객체를 생성합니다. (Setter 필요 없음!)
                    // public GameCharacter(String userIgn, String ocid) 생성자 호출
                    GameCharacter newChar = new GameCharacter(cleanUserIgn, ocid);

                    // 4. 저장 및 즉시 반영
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