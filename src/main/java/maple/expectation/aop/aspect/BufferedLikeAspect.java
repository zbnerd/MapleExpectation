package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class BufferedLikeAspect {

    private final LikeBufferStorage likeBufferStorage;

    // 🎯 [리팩토링] args(userIgn, ..)를 통해 첫 번째 인자를 String 타입으로 직접 바인딩
    @Around("@annotation(maple.expectation.aop.annotation.BufferedLike) && args(userIgn, ..)")
    public Object doBuffer(ProceedingJoinPoint joinPoint, String userIgn) throws Throwable {

        // 더 이상 joinPoint.getArgs()[0]를 쓸 필요가 없습니다!
        likeBufferStorage.getCounter(userIgn).incrementAndGet();

        log.debug("📥 [AOP Buffering] 좋아요 요청이 버퍼에 기록되었습니다: {}", userIgn);

        // 실제 DB 로직인 proceed()는 호출하지 않고 스킵
        return null;
    }
}