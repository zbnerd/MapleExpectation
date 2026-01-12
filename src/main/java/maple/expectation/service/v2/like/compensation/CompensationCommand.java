package maple.expectation.service.v2.like.compensation;

import maple.expectation.service.v2.like.dto.FetchResult;

/**
 * 보상 트랜잭션 명령 인터페이스 (Command Pattern)
 *
 * <p>금융수준 안전 설계:
 * <ul>
 *   <li><b>save()</b>: 작업 전 상태 저장 (FetchResult)</li>
 *   <li><b>compensate()</b>: 실패 시 원복 (임시 키 → 원본 키)</li>
 *   <li><b>commit()</b>: 성공 시 정리 (임시 키 삭제)</li>
 * </ul>
 * </p>
 *
 * <p>사용 패턴:
 * <pre>{@code
 * CompensationCommand cmd = new RedisCompensationCommand(...);
 *
 * executor.executeWithFinally(
 *     () -> {
 *         FetchResult result = strategy.fetchAndMove(sourceKey, tempKey);
 *         cmd.save(result);
 *         processData(result);  // DB 저장 등
 *         cmd.commit();
 *     },
 *     () -> {
 *         if (cmd.isPending()) {
 *             cmd.compensate();  // 실패 시 복구
 *         }
 *     },
 *     context
 * );
 * }</pre>
 * </p>
 *
 * @since 2.0.0
 */
public interface CompensationCommand {

    /**
     * 작업 전 상태 저장
     *
     * @param result fetch 결과 (복구에 필요한 데이터)
     */
    void save(FetchResult result);

    /**
     * 실패 시 보상 트랜잭션 실행
     *
     * <p>임시 키 데이터를 원본 키로 복원</p>
     */
    void compensate();

    /**
     * 성공 시 커밋 (정리)
     *
     * <p>임시 키 삭제, 상태 초기화</p>
     */
    void commit();

    /**
     * 보상이 필요한 상태인지 확인
     *
     * @return true: save() 호출됨 + commit() 미호출 → 보상 필요
     */
    boolean isPending();
}
