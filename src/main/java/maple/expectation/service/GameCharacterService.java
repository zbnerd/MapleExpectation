package maple.expectation.service;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.LogExecutionTime;
import maple.expectation.domain.GameCharacter;
import maple.expectation.external.MaplestoryApiClient;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import maple.expectation.repository.GameCharacterRepository;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class GameCharacterService {

    private final GameCharacterRepository gameCharacterRepository;
    private final MaplestoryApiClient maplestoryApiClient;
    private final ApplicationContext applicationContext;


    @Transactional
    public String saveCharacter(GameCharacter character) {
        return gameCharacterRepository.save(character);
    }

    public GameCharacter findCharacterByUserIgn(String userIgn) {
        GameCharacter character = gameCharacterRepository.findByUserIgn(userIgn);

        if (character == null) {
            GameCharacter newCharacter = new GameCharacter();
            newCharacter.setOcid(maplestoryApiClient.getOcidByCharacterName(userIgn).getOcid());
            newCharacter.setUserIgn(userIgn);
            gameCharacterRepository.save(newCharacter);

            return newCharacter;
        }

        return gameCharacterRepository.findByUserIgn(userIgn);
    }

    // ❌ 1. [방어 없음] 일반적인 조회 -> 수정
    // 동시에 100명이 들어오면 서로 덮어써서 숫자가 씹힘 (Race Condition)
    @Transactional
    @LogExecutionTime
    public void clickLikeWithOutLock(String userIgn) {
        GameCharacter character = gameCharacterRepository.findByUserIgn(userIgn);
        character.like();
    }

    // ✅ 2. [비관적 락] 조회 시점부터 잠금
    // 동시에 100명이 들어와도 줄을 서서(Sequential) 처리됨 -> 데이터 정합성 보장

    @Transactional
    @LogExecutionTime
    public void clickLikeWithPessimisticLock(String userIgn) {
        GameCharacter character = gameCharacterRepository.findByUserIgnWithPessimisticLock(userIgn);
        character.like();
    }

    // ✅ 3. [낙관적 락]
    @LogExecutionTime
    public void clickLikeWithOptimisticLock(String userIgn) {
        // [프록시 객체 획득] - 현재 GameCharacterService의 프록시 객체를 가져옵니다.
        // 이를 통해 호출해야 @Transactional AOP가 작동합니다.
        GameCharacterService self = applicationContext.getBean(GameCharacterService.class);

        while (true) {
            try {
                // 프록시 객체를 통해 호출하여 새로운 트랜잭션 시작
                self.attemptOptimisticLike(userIgn);
                return; // 성공 시 루프 종료
            } catch (ObjectOptimisticLockingFailureException e) {
                // 충돌 감지! 재시도 로직
            }
        }
    }

    /**
     * ✅ 4. [Core Transaction] - 실제 DB 업데이트와 @Version 체크를 담당.
     * PUBLIC 메서드여야 AOP 프록시가 걸립니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptOptimisticLike(String userIgn) {
        // 락 없이 조회 (@Version 필드를 읽어옴)
        GameCharacter character = gameCharacterRepository.findByUserIgn(userIgn);

        character.like();
        // 메서드 종료 시 JPA가 UPDATE 쿼리를 날리고 @Version 체크
    }

    public Long getLikeCount(String userIgn) {
        return gameCharacterRepository.findByUserIgn(userIgn)
                .getLikeCount();
    }

}
