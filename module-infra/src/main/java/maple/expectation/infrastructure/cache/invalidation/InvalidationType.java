package maple.expectation.infrastructure.cache.invalidation;

/**
 * 캐시 무효화 유형 (Issue #278: L1 Cache Coherence)
 *
 * <h3>P0-1 반영: clear() 호출 시 다른 인스턴스 L1 무효화 지원</h3>
 *
 * <ul>
 *   <li>{@link #EVICT}: 특정 키 무효화
 *   <li>{@link #CLEAR_ALL}: 캐시 전체 무효화
 * </ul>
 *
 * <h3>5-Agent Council 만장일치 (P0-1)</h3>
 *
 * <p>Blue(Architect) 제기 → Purple(Auditor) 검증: evict()와 clear() 모두 무효화 필요
 */
public enum InvalidationType {

  /** 특정 키 무효화 */
  EVICT,

  /** 캐시 전체 무효화 */
  CLEAR_ALL
}
