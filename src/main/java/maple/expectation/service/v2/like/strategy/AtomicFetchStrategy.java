package maple.expectation.service.v2.like.strategy;

import maple.expectation.service.v2.like.dto.FetchResult;

/**
 * 원자적 fetch 전략 인터페이스 (Strategy Pattern)
 *
 * <p>금융수준 안전 설계 - SOLID 원칙:
 * <ul>
 *   <li><b>SRP</b>: 원자적 데이터 이동만 담당</li>
 *   <li><b>OCP</b>: 새 전략(예: Transaction) 추가 시 기존 코드 수정 없음</li>
 *   <li><b>LSP</b>: 모든 구현체는 동일한 계약(원자성) 보장</li>
 *   <li><b>ISP</b>: fetch/restore/delete 최소 인터페이스</li>
 *   <li><b>DIP</b>: LikeSyncService는 구체 클래스가 아닌 인터페이스에 의존</li>
 * </ul>
 * </p>
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code LuaScriptAtomicFetchStrategy} - Lua Script 기반 (Primary)</li>
 *   <li>{@code RenameAtomicFetchStrategy} - RENAME 기반 (Fallback)</li>
 * </ul>
 * </p>
 *
 * @since 2.0.0
 */
public interface AtomicFetchStrategy {

    /**
     * 원본 키 → 임시 키로 원자적 이동 후 데이터 반환
     *
     * <p>금융수준 안전:
     * <ul>
     *   <li>RENAME으로 원본 키 즉시 비움 (새 데이터는 원본 키에 축적)</li>
     *   <li>임시 키에 데이터 보존 (JVM 크래시 시 복구 가능)</li>
     *   <li>TTL 설정으로 영구 메모리 누수 방지</li>
     * </ul>
     * </p>
     *
     * @param sourceKey 원본 키 (Hash Tag 패턴: {buffer:likes})
     * @param tempKey   임시 키 (Hash Tag 패턴: {buffer:likes}:sync:{uuid})
     * @return fetch 결과 (임시 키 + 데이터 Map)
     */
    FetchResult fetchAndMove(String sourceKey, String tempKey);

    /**
     * 임시 키 데이터를 원본 키로 복원 (보상 트랜잭션)
     *
     * <p>DB 저장 실패 시 호출되어 데이터 유실 방지</p>
     *
     * @param tempKey   임시 키
     * @param sourceKey 원본 키
     */
    void restore(String tempKey, String sourceKey);

    /**
     * 임시 키 삭제 (DB 저장 성공 후 호출)
     *
     * <p>정상 완료 시에만 호출. 실패 시 TTL에 의해 자동 만료</p>
     *
     * @param tempKey 삭제할 임시 키
     */
    void deleteTempKey(String tempKey);

    /**
     * 전략 이름 (메트릭/로깅용)
     *
     * @return 전략 식별자 (예: "lua", "rename")
     */
    String strategyName();
}
