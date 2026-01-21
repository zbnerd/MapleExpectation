package maple.expectation.support.flaky;

import java.time.Instant;

/**
 * 테스트 실패 정보 Record (Issue #210)
 *
 * <p>Blue Agent 제안: ExtensionContext.Store 저장용 불변 Record</p>
 * <p>ThreadLocal 대신 Store에 저장하여 메모리 누수 방지</p>
 *
 * @param className    테스트 클래스 FQCN
 * @param methodName   테스트 메서드명
 * @param errorMessage 실패 에러 메시지
 * @param timestamp    실패 시각
 */
record TestFailureInfo(
        String className,
        String methodName,
        String errorMessage,
        Instant timestamp
) {
}
