package maple.expectation.infrastructure.queue;

/**
 * 메시지 큐 타입 식별자
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 *
 * <p>ADR-012 WriteBackBufferStrategy 설계 반영
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): OCP - 설정값 하나로 전략 교체
 *   <li>Red (SRE): 모니터링 태그로 사용
 * </ul>
 *
 * <h3>전략 비교</h3>
 *
 * <table>
 *   <tr><th>전략</th><th>지연</th><th>처리량</th><th>내구성</th><th>비용</th><th>전환 시점</th></tr>
 *   <tr><td>IN_MEMORY</td><td>0.1ms</td><td>965 RPS</td><td>Low</td><td>Free</td><td>V4 현재</td></tr>
 *   <tr><td>REDIS_LIST</td><td>1-2ms</td><td>500 RPS/대</td><td>Medium</td><td>$</td><td>V5 800+ RPS</td></tr>
 *   <tr><td>KAFKA</td><td>5-10ms</td><td>무제한</td><td>High</td><td>$$$</td><td>V6 10,000+ RPS</td></tr>
 *   <tr><td>SQS</td><td>10-20ms</td><td>무제한</td><td>High</td><td>$$</td><td>서버리스 전환</td></tr>
 * </table>
 *
 * <h3>application.yml 설정</h3>
 *
 * <pre>
 * message-queue:
 *   strategy: ${MQ_STRATEGY:in-memory}  # in-memory | redis | kafka | sqs
 * </pre>
 */
public enum QueueType {

  /**
   * V4: ConcurrentLinkedQueue (In-Memory)
   *
   * <ul>
   *   <li>장점: 0.1ms 지연, 비용 없음
   *   <li>단점: Scale-out 불가, 장애 시 데이터 유실
   *   <li>적합: 단일 노드, 트래픽 < 1,000 RPS
   * </ul>
   */
  IN_MEMORY("in-memory"),

  /**
   * V5: Redis List (RPUSH/LPOP + INFLIGHT 패턴)
   *
   * <ul>
   *   <li>장점: Scale-out 가능, At-Least-Once 보장
   *   <li>단점: 네트워크 RTT 추가 (1-2ms)
   *   <li>적합: 다중 노드, 트래픽 800+ RPS
   * </ul>
   */
  REDIS_LIST("redis"),

  /**
   * V6: Kafka Topic (Future)
   *
   * <ul>
   *   <li>장점: 무제한 확장, Exactly-Once 가능
   *   <li>단점: 운영 복잡도 높음, 비용 높음
   *   <li>적합: 대규모 이벤트, 트래픽 10,000+ RPS
   * </ul>
   */
  KAFKA("kafka"),

  /**
   * AWS SQS (Option)
   *
   * <ul>
   *   <li>장점: 서버리스, 관리 오버헤드 최소
   *   <li>단점: 지연 10-20ms
   *   <li>적합: 서버리스 아키텍처
   * </ul>
   */
  SQS("sqs");

  private final String configValue;

  QueueType(String configValue) {
    this.configValue = configValue;
  }

  /**
   * application.yml 설정값
   *
   * @return 설정값 (in-memory, redis, kafka, sqs)
   */
  public String getConfigValue() {
    return configValue;
  }

  /**
   * 설정값으로 QueueType 조회
   *
   * @param configValue 설정값
   * @return QueueType (찾지 못하면 IN_MEMORY 기본값)
   */
  public static QueueType fromConfigValue(String configValue) {
    for (QueueType type : values()) {
      if (type.configValue.equalsIgnoreCase(configValue)) {
        return type;
      }
    }
    return IN_MEMORY; // 기본값
  }
}
