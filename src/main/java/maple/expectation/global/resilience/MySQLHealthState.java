package maple.expectation.global.resilience;

/**
 * MySQL 상태 머신 (P0-2: Flapping 방지)
 *
 * <h4>상태 전이 다이어그램</h4>
 * <pre>
 * HEALTHY ──(CB OPEN)──▶ DEGRADED ──(CB CLOSED)──▶ RECOVERING ──(5초)──▶ HEALTHY
 *    ▲                                                                      │
 *    └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h4>상태 설명</h4>
 * <ul>
 *   <li><b>HEALTHY:</b> MySQL 정상 동작 중</li>
 *   <li><b>DEGRADED:</b> MySQL 장애 감지됨, TTL 무한대 설정 및 Fallback 활성화</li>
 *   <li><b>RECOVERING:</b> MySQL 복구 감지됨, Compensation Sync 진행 중</li>
 * </ul>
 */
public enum MySQLHealthState {

    /**
     * MySQL 정상 동작 상태
     */
    HEALTHY {
        @Override
        public MySQLHealthState onCircuitBreakerOpen() {
            return DEGRADED;
        }

        @Override
        public MySQLHealthState onCircuitBreakerClosed() {
            return HEALTHY; // 이미 정상, 상태 유지
        }

        @Override
        public MySQLHealthState onRecoveryComplete() {
            return HEALTHY; // 이미 정상, 상태 유지
        }
    },

    /**
     * MySQL 장애 감지 상태 (Degraded Mode)
     *
     * <p>이 상태에서는:</p>
     * <ul>
     *   <li>캐시 TTL이 무한대로 설정됨</li>
     *   <li>Nexon API Fallback이 활성화됨</li>
     *   <li>Compensation Log에 변경 사항 기록</li>
     * </ul>
     */
    DEGRADED {
        @Override
        public MySQLHealthState onCircuitBreakerOpen() {
            return DEGRADED; // 이미 장애 상태, 유지
        }

        @Override
        public MySQLHealthState onCircuitBreakerClosed() {
            return RECOVERING;
        }

        @Override
        public MySQLHealthState onRecoveryComplete() {
            return DEGRADED; // 복구 중이 아님, 상태 유지
        }
    },

    /**
     * MySQL 복구 진행 상태
     *
     * <p>이 상태에서는:</p>
     * <ul>
     *   <li>Compensation Log를 DB에 동기화</li>
     *   <li>캐시 TTL을 원래 값으로 복원</li>
     *   <li>5초 Debounce 후 HEALTHY로 전이</li>
     * </ul>
     */
    RECOVERING {
        @Override
        public MySQLHealthState onCircuitBreakerOpen() {
            // 복구 중 다시 장애 발생 (Flapping)
            return DEGRADED;
        }

        @Override
        public MySQLHealthState onCircuitBreakerClosed() {
            return RECOVERING; // 이미 복구 중, 상태 유지
        }

        @Override
        public MySQLHealthState onRecoveryComplete() {
            return HEALTHY;
        }
    };

    /**
     * CircuitBreaker OPEN 이벤트 처리
     *
     * @return 전이 후 상태
     */
    public abstract MySQLHealthState onCircuitBreakerOpen();

    /**
     * CircuitBreaker CLOSED 이벤트 처리
     *
     * @return 전이 후 상태
     */
    public abstract MySQLHealthState onCircuitBreakerClosed();

    /**
     * 복구 완료 이벤트 처리 (Debounce 타임아웃 후)
     *
     * @return 전이 후 상태
     */
    public abstract MySQLHealthState onRecoveryComplete();

    /**
     * 장애 모드 여부 확인
     *
     * @return DEGRADED 또는 RECOVERING 상태이면 true
     */
    public boolean isDegraded() {
        return this == DEGRADED || this == RECOVERING;
    }

    /**
     * 정상 상태 여부 확인
     *
     * @return HEALTHY 상태이면 true
     */
    public boolean isHealthy() {
        return this == HEALTHY;
    }
}
