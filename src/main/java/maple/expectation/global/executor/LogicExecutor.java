package maple.expectation.global.executor;

import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.strategy.ExceptionTranslator;

import java.util.function.Function;

/**
 * 예외 처리 패턴을 추상화한 실행기
 *
 * <p>코드 평탄화(Code Flattening)를 최우선으로 설계되었습니다.
 * 비즈니스 로직은 별도 메서드로 분리하고 메서드 참조({@code this::method})를 활용하세요.
 *
 * <h3>6가지 예외 처리 패턴 지원</h3>
 * <ol>
 *   <li><b>try-finally</b> (리소스 정리) - {@link #executeWithFinally}</li>
 *   <li><b>try-catch-throw</b> (예외 변환 후 재전파) - {@link #execute}</li>
 *   <li><b>try-catch-return</b> (기본값 반환) - {@link #executeOrDefault}</li>
 *   <li><b>try-catch-log</b> (로그만 기록) - {@link #executeOrDefault} 또는 {@link #executeVoid}</li>
 *   <li><b>try-catch-recover</b> (복구 로직 실행) - {@link #executeWithRecovery}</li>
 *   <li><b>다중 catch</b> (ExceptionTranslator 사용) - {@link #executeWithTranslation}</li>
 * </ol>
 *
 * <h3>사용 예시 (Good Pattern)</h3>
 * <pre>{@code
 * public void goodExample() {
 *     logicExecutor.execute(this::businessLogic, "task");
 * }
 *
 * private String businessLogic() throws Exception {
 *     // 핵심 로직만 집중
 *     return "result";
 * }
 * }</pre>
 *
 * <h3>안티 패턴 (Bad Pattern - 절대 금지)</h3>
 * <pre>{@code
 * public void badExample() {
 *     logicExecutor.execute(() -> {
 *         // 람다 안에 수십 줄의 로직 (괄호 지옥)
 *         if (condition) {
 *             // ...
 *         }
 *     }, "task");
 * }
 * }</pre>
 *
 * @see ThrowingSupplier
 * @see ThrowingRunnable
 * @see ExceptionTranslator
 * @since 1.0.0
 */
public interface LogicExecutor {

    /**
     * [패턴 1, 2] 예외를 RuntimeException으로 변환하여 전파
     *
     * <p>체크 예외(Checked Exception)를 자동으로 언체크 예외(RuntimeException)로 변환합니다.
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * String result = executor.execute(this::fetchData, "fetchData");
     *
     * private String fetchData() throws IOException {
     *     return Files.readString(Path.of("data.txt"));
     * }
     * }</pre>
     *
     * @param <T> 작업 결과 타입
     * @param task 실행할 작업
     * @param taskName 작업 이름 (로깅/메트릭용)
     * @return 작업 결과
     * @throws RuntimeException 변환된 예외
     */
    <T> T execute(ThrowingSupplier<T> task, String taskName);

    /**
     * [패턴 3, 4] 예외 발생 시 기본값 반환
     *
     * <p>예외 발생 시 에러 로그를 남기고 기본값을 반환합니다.
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * String config = executor.executeOrDefault(
     *     this::loadConfig,
     *     "default-config",
     *     "loadConfig"
     * );
     * }</pre>
     *
     * @param <T> 작업 결과 타입
     * @param task 실행할 작업
     * @param defaultValue 예외 발생 시 반환할 기본값
     * @param taskName 작업 이름
     * @return 작업 결과 또는 기본값
     */
    <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, String taskName);

    /**
     * [패턴 5] 예외 발생 시 복구 로직 실행
     *
     * <p>예외 발생 시 예외 객체를 받아 복구값을 생성하는 함수를 실행합니다.
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * String result = executor.executeWithRecovery(
     *     this::fetchFromPrimary,
     *     e -> this.fetchFromSecondary(),
     *     "fetchData"
     * );
     * }</pre>
     *
     * @param <T> 작업 결과 타입
     * @param task 실행할 작업
     * @param recovery 예외를 받아 복구값을 생성하는 함수
     * @param taskName 작업 이름
     * @return 작업 결과 또는 복구값
     */
    <T> T executeWithRecovery(
        ThrowingSupplier<T> task,
        Function<Throwable, T> recovery,
        String taskName
    );

    /**
     * [패턴 1] void 작업 실행 (리소스 정리)
     *
     * <p>반환값이 없는 작업을 실행합니다.
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * executor.executeVoid(this::cleanup, "cleanup");
     *
     * private void cleanup() throws IOException {
     *     Files.deleteIfExists(tempFile);
     * }
     * }</pre>
     *
     * @param task 실행할 작업
     * @param taskName 작업 이름
     */
    void executeVoid(ThrowingRunnable task, String taskName);

    /**
     * [패턴 1] finally 블록을 명시적으로 지정
     *
     * <p>작업 실행 후 반드시 실행할 정리 작업을 지정합니다.
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * String result = executor.executeWithFinally(
     *     this::fetchData,
     *     this::closeConnection,
     *     "fetchData"
     * );
     * }</pre>
     *
     * @param <T> 작업 결과 타입
     * @param task 실행할 작업
     * @param finallyBlock 반드시 실행할 정리 작업
     * @param taskName 작업 이름
     * @return 작업 결과
     */
    <T> T executeWithFinally(
        ThrowingSupplier<T> task,
        Runnable finallyBlock,
        String taskName
    );

    /**
     * [패턴 6] 특정 예외를 도메인 예외로 변환
     *
     * <p>여러 종류의 예외를 도메인별 예외로 변환하는 전략을 지정합니다.
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * String json = executor.executeWithTranslation(
     *     () -> objectMapper.writeValueAsString(data),
     *     ExceptionTranslator.forJson(),
     *     "serialize"
     * );
     * }</pre>
     *
     * @param <T> 작업 결과 타입
     * @param task 실행할 작업
     * @param translator 예외 변환기
     * @param taskName 작업 이름
     * @return 작업 결과
     */
    <T> T executeWithTranslation(
        ThrowingSupplier<T> task,
        ExceptionTranslator translator,
        String taskName
    );
}
