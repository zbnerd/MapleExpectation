package maple.expectation.global.queue;

/**
 * Redis Key 상수 (Hash Tag 패턴 - Cluster CROSSSLOT 방지)
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 * <p>GPT-5 Iteration 4 반영: Redis Cluster 환경에서 다중 키 연산 시
 * CROSSSLOT 오류 방지를 위한 Hash Tag 패턴 적용</p>
 *
 * <h3>Hash Tag 규칙</h3>
 * <p>Redis Cluster는 키의 {@code {}} 내부 문자열로 슬롯을 결정합니다.
 * 동일한 Hash Tag를 가진 키들은 같은 슬롯에 배치되어 원자적 연산이 가능합니다.</p>
 *
 * <h3>예시</h3>
 * <pre>
 * {expectation}:buffer        → slot X
 * {expectation}:buffer:inflight → slot X (같은 슬롯)
 * {likes}:buffer              → slot Y (다른 슬롯)
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): Enum으로 키 중앙화 관리</li>
 *   <li>Green (Performance): Hash Tag로 Lua Script 최적화</li>
 *   <li>Red (SRE): 일관된 키 네이밍으로 모니터링 용이</li>
 * </ul>
 */
public enum RedisKey {

    // ============================================================
    // Write-Behind Buffer (INFLIGHT 패턴 + msgId 분리)
    // ============================================================

    /**
     * Main Queue (List of msgId)
     * <p>RPUSH로 추가, RPOPLPUSH로 INFLIGHT로 이동</p>
     */
    EXPECTATION_BUFFER("{expectation}:buffer"),

    /**
     * Processing Queue (List of msgId)
     * <p>처리 중인 메시지. RPOPLPUSH 대상</p>
     */
    EXPECTATION_BUFFER_INFLIGHT("{expectation}:buffer:inflight"),

    /**
     * 만료 판정용 ZSET
     * <p>score = timestamp, member = msgId. Re-drive Scheduler가 조회</p>
     */
    EXPECTATION_BUFFER_INFLIGHT_TS("{expectation}:buffer:inflight:ts"),

    /**
     * Payload Store (HASH: msgId → JSON)
     * <p>GPT-5 Iteration 4 (B): Re-drive 시 payload 복원 가능</p>
     */
    EXPECTATION_BUFFER_PAYLOAD("{expectation}:buffer:payload"),

    /**
     * Delayed Retry Queue (ZSET)
     * <p>score = nextAttemptAtMs, member = msgId. 지연 재시도</p>
     */
    EXPECTATION_BUFFER_RETRY("{expectation}:buffer:retry"),

    /**
     * Dead Letter Queue (List)
     * <p>최대 재시도 초과 메시지. 수동 처리 필요</p>
     */
    EXPECTATION_BUFFER_DLQ("{expectation}:buffer:dlq"),

    /**
     * Checkpoint (String)
     * <p>마지막 플러시 ID 저장. 크래시 복구용</p>
     */
    EXPECTATION_BUFFER_CHECKPOINT("{expectation}:checkpoint"),

    // ============================================================
    // Like Buffer (Partitioned)
    // ============================================================

    /**
     * Like 카운터 버퍼 (HASH: userIgn → delta)
     * <p>HINCRBY로 증분, 스케줄러가 주기적으로 DB 동기화</p>
     */
    LIKE_BUFFER("{likes}:buffer"),

    /**
     * Like 총합 카운터 (HASH: userIgn → totalLikes)
     */
    LIKE_BUFFER_TOTAL("{likes}:total"),

    /**
     * Like 관계 (SET: fromIgn → Set&lt;toIgn&gt;)
     */
    LIKE_RELATIONS("{likes}:relations"),

    /**
     * Like 관계 대기열 (SET)
     * <p>DB 동기화 대기 중인 관계</p>
     */
    LIKE_RELATIONS_PENDING("{likes}:relations:pending"),

    /**
     * Partitioned Flush Lock 접두사
     * <p>각 파티션별 분산 락. 예: {likes}:flush:partition:0</p>
     */
    LIKE_FLUSH_PARTITION("{likes}:flush:partition:"),

    // ============================================================
    // Persistence Tracking
    // ============================================================

    /**
     * 영속화 추적 (SET)
     * <p>Scale-out 환경에서 분산 추적</p>
     */
    PERSISTENCE_TRACKING("{persistence}:tracking"),

    // ============================================================
    // Idempotency (Key-per-id 방식)
    // ============================================================

    /**
     * 멱등성 키 접두사
     * <p>개별 키 + TTL로 관리. 예: {idempotency}:job:user123:req456</p>
     */
    IDEMPOTENCY_PREFIX("{idempotency}:"),

    // ============================================================
    // Sync Temp Keys
    // ============================================================

    /**
     * 동기화 임시 키 접두사
     */
    SYNC_TEMP_PREFIX("{likes}:sync:"),

    // ============================================================
    // Like Realtime Sync (Issue #278: Scale-out Pub/Sub)
    // ============================================================

    /**
     * 좋아요 이벤트 Pub/Sub 토픽
     * <p>Scale-out 환경에서 인스턴스 간 L1 캐시 무효화 이벤트 전파</p>
     * <p>Hash Tag {likes}로 같은 슬롯 배치</p>
     */
    LIKE_EVENTS_TOPIC("{likes}:events");

    private final String key;

    RedisKey(String key) {
        this.key = key;
    }

    /**
     * Redis 키 반환
     *
     * @return Hash Tag가 포함된 키
     */
    public String getKey() {
        return key;
    }

    /**
     * 접미사가 추가된 키 반환
     *
     * <h4>예시</h4>
     * <pre>
     * LIKE_FLUSH_PARTITION.withSuffix("0")
     * → "{likes}:flush:partition:0"
     * </pre>
     *
     * @param suffix 접미사
     * @return 키 + 접미사
     */
    public String withSuffix(String suffix) {
        return key + suffix;
    }

    /**
     * Hash Tag만 추출
     *
     * <h4>예시</h4>
     * <pre>
     * EXPECTATION_BUFFER.getHashTag() → "expectation"
     * </pre>
     *
     * @return {} 내부의 Hash Tag
     */
    public String getHashTag() {
        int start = key.indexOf('{');
        int end = key.indexOf('}');
        if (start >= 0 && end > start) {
            return key.substring(start + 1, end);
        }
        return key;
    }
}
