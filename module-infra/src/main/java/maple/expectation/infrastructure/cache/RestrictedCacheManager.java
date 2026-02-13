package maple.expectation.infrastructure.cache;

import java.util.Collection;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * L2 CacheManager 봉쇄용 래퍼 (P0-7/B3)
 *
 * <p>허용된 캐시 이름만 접근 가능하도록 구조적으로 제한합니다. disableCreateOnMissingCache() 지원 여부와 무관하게 동작합니다.
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
public class RestrictedCacheManager implements CacheManager {
  private final CacheManager delegate;
  private final Set<String> allowedCacheNames;

  public RestrictedCacheManager(CacheManager delegate, Set<String> allowedCacheNames) {
    this.delegate = delegate;
    this.allowedCacheNames = allowedCacheNames;
  }

  @Override
  public Cache getCache(String name) {
    if (!allowedCacheNames.contains(name)) {
      return null; // equipment 등 미등록 캐시는 null 반환 (구조적 봉쇄)
    }
    return delegate.getCache(name);
  }

  /** 의도적으로 축소 반환: delegate가 반환하는 이름과 불일치해도 정상. L2에는 expectationResult만 허용하므로, 이 값만 반환한다. */
  @Override
  public Collection<String> getCacheNames() {
    return allowedCacheNames;
  }
}
