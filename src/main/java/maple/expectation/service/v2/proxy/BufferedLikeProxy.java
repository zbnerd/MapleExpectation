package maple.expectation.service.v2.proxy;

import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.LikeProcessor;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary // 서비스에서 LikeProcessor 주입 시 이 프록시가 선택됨
@RequiredArgsConstructor
public class BufferedLikeProxy implements LikeProcessor {

    private final LikeBufferStorage likeBufferStorage;
    // 참고: Write-Back 방식에서는 즉시 'target'인 DatabaseLikeProcessor를 호출하지 않고
    // 스케줄러가 나중에 일괄 처리합니다.

    @Override
    public void processLike(String userIgn) {
        // 서비스로부터 요청을 가로채서 캐시 버퍼에 원자적으로 저장
        likeBufferStorage.getCounter(userIgn).incrementAndGet();
    }
}