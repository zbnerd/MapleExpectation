package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.exception.CharacterNotFoundException;
import maple.expectation.external.NexonApiClient;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.impl.DatabaseLikeProcessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@TraceLog
@Transactional(readOnly = true)
public class GameCharacterService {

    private final GameCharacterRepository gameCharacterRepository;
    private final NexonApiClient nexonApiClient;
    private final LikeProcessor likeProcessor; // @Primary인 BufferedLikeProxy가 주입됨
    private final DatabaseLikeProcessor databaseLikeProcessor; // 직접 DB 반영용

    // 순환 참조 방지를 위해 한 쪽에 @Lazy를 적용합니다.
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
    public GameCharacter findCharacterByUserIgn(String userIgn) {
        String cleanUserIgn = userIgn.trim();
        return gameCharacterRepository.findByUserIgn(cleanUserIgn)
                .orElseGet(() -> {
                    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).getOcid();
                    GameCharacter newChar = new GameCharacter(cleanUserIgn);
                    newChar.setOcid(ocid);
                    return gameCharacterRepository.save(newChar);
                });
    }

    /**
     * 🚀 [V2용] 기본 프록시(Caffeine 캐시 버퍼) 사용
     * 처리량(Throughput) 최우선 전략
     */
    public void clickLike(String userIgn) {
        likeProcessor.processLike(userIgn);
    }

    /**
     * 🔒 [V1용] 비관적 락 강제 사용 (DB 즉시 반영)
     * 데이터 정합성 최우선 전략
     */
    public void clickLikePessimistic(String userIgn) {
        databaseLikeProcessor.processLike(userIgn);
    }

    /**
     * 중앙 집중식 조회 메서드 (프로세서들이 사용)
     */
    public GameCharacter getCharacterOrThrow(String userIgn) {
        return gameCharacterRepository.findByUserIgn(userIgn)
                .orElseThrow(CharacterNotFoundException::new);
    }

    /**
     * 중앙 집중식 비관적 락 조회 메서드 (DatabaseLikeProcessor가 사용)
     */
    public GameCharacter getCharacterForUpdate(String userIgn) {
        return gameCharacterRepository.findByUserIgnWithPessimisticLock(userIgn)
                .orElseThrow(CharacterNotFoundException::new);
    }
}