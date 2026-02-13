package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * 캐시 데이터 미발견 예외
 *
 * <h3>사용 사례</h3>
 *
 * <p>TieredCache에서 데이터를 조회했으나 null/empty인 경우. 캐시 로직의 버그를 의심할 수 있는 상황입니다.
 *
 * <h3>Circuit Breaker</h3>
 *
 * <p>캐시 데이터 부재는 시스템 장애로 분류 (CircuitBreakerRecordMarker)
 */
public class CacheDataNotFoundException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  public CacheDataNotFoundException(String cacheKey) {
    super(CommonErrorCode.CACHE_DATA_NOT_FOUND, cacheKey);
  }
}
