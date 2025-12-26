package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LikeSyncExecutor {

    private final GameCharacterRepository gameCharacterRepository;

    /**
     * 개별 유저의 좋아요 수를 DB에 물리적으로 반영
     * Propagation.REQUIRES_NEW: 각 유저별로 독립된 트랜잭션을 보장하여
     * 한 명이라도 실패해도 다른 유저의 동기화에 영향을 주지 않음.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeIncrement(String userIgn, long count) {
        gameCharacterRepository.incrementLikeCount(userIgn, count);
    }
}