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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameCharacterService {

    private final GameCharacterRepository gameCharacterRepository;
    private final NexonApiClient nexonApiClient;
    private final LikeProcessor likeProcessor;
    private final DatabaseLikeProcessor databaseLikeProcessor;

    /**
     * ⚡ [RPS 최적화] 락 없이 DB 존재 여부만 확인
     */
    public Optional<GameCharacter> getCharacterIfExist(String userIgn) {
        return gameCharacterRepository.findByUserIgn(userIgn.trim());
    }

    /**
     * 🔒 [신규 생성] 실제 넥슨 API를 호출하고 DB에 저장하는 구간 (락 내부에서 실행)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
    public GameCharacter createNewCharacter(String userIgn) {
        String cleanUserIgn = userIgn.trim();

        // Double-Check: 락 획득 대기 중에 다른 스레드가 생성했을 수 있음
        return gameCharacterRepository.findByUserIgn(cleanUserIgn)
                .orElseGet(() -> {
                    log.info("✨ [First Creation] 신규 캐릭터 생성: {}", cleanUserIgn);
                    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).getOcid();
                    return gameCharacterRepository.saveAndFlush(new GameCharacter(cleanUserIgn, ocid));
                });
    }

    @Transactional
    public String saveCharacter(GameCharacter character) {
        return gameCharacterRepository.save(character).getUserIgn();
    }

    // 🔥 [관측 가능성 유지] 좋아요 메트릭 및 로그
    @LogExecutionTime
    @ObservedTransaction("service.v2.GameCharacterService.clickLikeCache")
    public void clickLikeCache(String userIgn) {
        likeProcessor.processLike(userIgn);
    }

    // 🔥 [관측 가능성 유지] 비관적 락 좋아요 메트릭
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