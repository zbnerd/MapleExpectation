package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeSyncService {

    private final LikeBufferStorage likeBufferStorage;
    private final GameCharacterRepository gameCharacterRepository;

    @Transactional
    @ObservedTransaction("scheduler.like.sync")
    public void syncLikesToDatabase() {
        Map<String, AtomicLong> bufferMap = likeBufferStorage.getCache().asMap();
        if (bufferMap.isEmpty()) return;

        log.debug("[Sync] 데이터 동기화 시작 (대상 유저 수: {})", bufferMap.size());

        // 람다 본문을 메서드로 추출하여 가독성 확보
        bufferMap.forEach(this::syncEachUserLike);
    }

    private void syncEachUserLike(String userIgn, AtomicLong atomicCount) {
        long countToAdd = atomicCount.getAndSet(0);
        
        // [Guard Clause] 반영할 데이터가 없으면 즉시 리턴 (indent 제거)
        if (countToAdd <= 0) return;

        try {
            gameCharacterRepository.incrementLikeCount(userIgn, countToAdd);
        } catch (Exception e) {
            rollbackBuffer(userIgn, atomicCount, countToAdd, e);
        }
    }

    private void rollbackBuffer(String userIgn, AtomicLong atomicCount, long countToAdd, Exception e) {
        log.error("[Sync Error] {} 반영 실패. 데이터 복구 시도 (발생 에러: {})", userIgn, e.getMessage());
        atomicCount.addAndGet(countToAdd); 
    }
}