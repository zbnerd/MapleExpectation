package maple.expectation.global.redis.script;

/**
 * Redis Lua Script 상수 클래스
 *
 * <p>Redis 싱글 스레드 특성을 활용하여 원자적 연산을 보장합니다.
 * 모든 스크립트는 Hash Tag {likes}를 사용하여 Redis Cluster CROSSSLOT 에러를 방지합니다.</p>
 *
 * <h2>Key Naming Convention</h2>
 * <pre>
 * buffer:{likes}:hash        - Hash (사용자별 좋아요 카운트)
 * buffer:{likes}:total_count - String (전역 대기 카운트)
 * buffer:{likes}:sync:{uuid} - Hash (동기화 임시 키)
 * </pre>
 */
public final class LuaScripts {

    private LuaScripts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Redis Key 상수
     */
    public static final class Keys {
        public static final String HASH = "buffer:{likes}:hash";
        public static final String TOTAL_COUNT = "buffer:{likes}:total_count";
        public static final String SYNC_PREFIX = "buffer:{likes}:sync:";

        private Keys() {
            throw new UnsupportedOperationException("Utility class");
        }
    }

    /**
     * Script 1: AtomicTransferScript (L1 → L2)
     *
     * <p>로컬 버퍼에서 Redis로 원자적 전송</p>
     *
     * <pre>
     * KEYS[1] = buffer:{likes}:hash (Hash)
     * KEYS[2] = buffer:{likes}:total_count (String)
     * ARGV[1] = userIgn (사용자 식별자)
     * ARGV[2] = count (증가할 값)
     *
     * Returns: 1 (항상 성공)
     * </pre>
     *
     * <p>원자성: HINCRBY + INCRBY가 단일 트랜잭션으로 실행되어
     * 중간 크래시 시 데이터 불일치 방지</p>
     */
    public static final String ATOMIC_TRANSFER = """
            redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
            redis.call('INCRBY', KEYS[2], ARGV[2])
            return 1
            """;

    /**
     * Script 2: AtomicDeleteAndDecrementScript (Cleanup - 멱등성 보장)
     *
     * <p>동기화 완료 후 임시 키에서 엔트리 삭제 및 전역 카운터 차감</p>
     *
     * <pre>
     * KEYS[1] = tempKey (동기화 임시 키)
     * KEYS[2] = buffer:{likes}:total_count (String)
     * ARGV[1] = userIgn (사용자 식별자)
     * ARGV[2] = count (차감할 값)
     *
     * Returns:
     *   1 = 정상 삭제 및 차감
     *   0 = 이미 삭제됨 (멱등성 - DECRBY 스킵)
     * </pre>
     *
     * <p>멱등성: 중복 실행 시 HDEL이 0을 반환하면 DECRBY를 스킵하여
     * 카운터 중복 차감 방지</p>
     */
    public static final String ATOMIC_DELETE_AND_DECREMENT = """
            local deleted = redis.call('HDEL', KEYS[1], ARGV[1])
            if deleted > 0 then
                redis.call('DECRBY', KEYS[2], ARGV[2])
            end
            return deleted
            """;

    /**
     * Script 3: AtomicCompensationScript (DB 실패 복구)
     *
     * <p>DB 반영 실패 시 원본 버퍼로 데이터 복구</p>
     *
     * <pre>
     * KEYS[1] = buffer:{likes}:hash (원본 Hash)
     * KEYS[2] = tempKey (동기화 임시 키)
     * ARGV[1] = userIgn (사용자 식별자)
     * ARGV[2] = count (복구할 값)
     *
     * Returns: 1 (항상 성공)
     * </pre>
     *
     * <p>원자성: HINCRBY(복구) + HDEL(임시키 정리)가 단일 트랜잭션으로 실행되어
     * 복구 중 크래시 시 데이터 중복/유실 방지</p>
     */
    public static final String ATOMIC_COMPENSATION = """
            redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
            redis.call('HDEL', KEYS[2], ARGV[1])
            return 1
            """;
}
