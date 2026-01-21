package maple.expectation.support.flaky;

import java.time.Instant;

/**
 * Flaky Test 이벤트 Record (Issue #210)
 *
 * <p>Purple Agent 승인: 불변 Record로 데이터 무결성 보장</p>
 * <p>CLAUDE.md Section 24 Observability 원칙 준수</p>
 *
 * @param timestamp    이벤트 발생 시각 (ISO-8601)
 * @param gitCommit    Git 커밋 해시 (7자리)
 * @param className    테스트 클래스 FQCN
 * @param methodName   테스트 메서드명
 * @param errorMessage 최초 실패 에러 메시지
 * @param retryCount   재시도 횟수
 */
public record FlakyEvent(
        Instant timestamp,
        String gitCommit,
        String className,
        String methodName,
        String errorMessage,
        int retryCount
) {
    /**
     * Flaky 이벤트 생성 팩토리 메서드
     *
     * @param className    테스트 클래스 FQCN
     * @param methodName   테스트 메서드명
     * @param errorMessage 에러 메시지 (null 허용)
     * @param gitCommit    Git 커밋 해시
     * @return 새 FlakyEvent 인스턴스
     */
    public static FlakyEvent of(String className, String methodName,
                                 String errorMessage, String gitCommit) {
        return new FlakyEvent(
                Instant.now(),
                gitCommit != null ? gitCommit : "unknown",
                className,
                methodName,
                errorMessage != null ? errorMessage : "unknown",
                1
        );
    }
}
