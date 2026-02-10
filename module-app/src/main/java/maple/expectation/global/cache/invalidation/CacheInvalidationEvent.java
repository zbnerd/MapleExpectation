package maple.expectation.global.cache.invalidation;

import java.io.Serializable;

/**
 * 캐시 무효화 이벤트 DTO (Issue #278: L1 Cache Coherence)
 *
 * <h3>Scale-out 환경에서 TieredCache L1(Caffeine) 캐시 무효화를 위한 이벤트 메시지</h3>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Record로 불변성 보장, LikeEvent와 동일 패턴
 *   <li>Green (Performance): 최소한의 필드로 네트워크 부하 최소화
 *   <li>Purple (Data): InvalidationType으로 EVICT/CLEAR_ALL 구분
 *   <li>Red (SRE): sourceInstanceId로 Self-skip 보장
 * </ul>
 *
 * <h3>P0-1 반영: CLEAR_ALL 지원</h3>
 *
 * <p>clear() 호출 시 다른 인스턴스의 L1 캐시도 전체 무효화
 *
 * @param cacheName 캐시 이름 (예: "character", "characterBasic")
 * @param key 캐시 키 ({@link InvalidationType#CLEAR_ALL}일 경우 null)
 * @param sourceInstanceId 발행 인스턴스 ID (Self-skip용)
 * @param type 무효화 유형 (EVICT 또는 CLEAR_ALL)
 * @param timestamp 발행 시각 (디버깅/메트릭용)
 */
public record CacheInvalidationEvent(
    String cacheName, String key, String sourceInstanceId, InvalidationType type, long timestamp)
    implements Serializable {

  /**
   * 특정 키 무효화 이벤트 생성
   *
   * @param cacheName 캐시 이름
   * @param key 캐시 키
   * @param instanceId 발행 인스턴스 ID
   * @return EVICT 타입 이벤트
   */
  public static CacheInvalidationEvent evict(String cacheName, String key, String instanceId) {
    return new CacheInvalidationEvent(
        cacheName, key, instanceId, InvalidationType.EVICT, System.currentTimeMillis());
  }

  /**
   * 캐시 전체 무효화 이벤트 생성
   *
   * @param cacheName 캐시 이름
   * @param instanceId 발행 인스턴스 ID
   * @return CLEAR_ALL 타입 이벤트
   */
  public static CacheInvalidationEvent clearAll(String cacheName, String instanceId) {
    return new CacheInvalidationEvent(
        cacheName, null, instanceId, InvalidationType.CLEAR_ALL, System.currentTimeMillis());
  }
}
