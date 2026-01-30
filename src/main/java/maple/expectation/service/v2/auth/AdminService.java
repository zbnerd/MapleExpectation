package maple.expectation.service.v2.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.InvalidAdminFingerprintException;
import maple.expectation.global.util.StringMaskingUtils;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin 권한 관리 서비스 (Redis 기반)
 *
 * <p>Admin 관리 전략:
 * <ul>
 *   <li>Bootstrap Admin: 환경변수(ADMIN_FINGERPRINTS)로 초기 설정</li>
 *   <li>Runtime Admin: Redis Set에 저장, API로 CRUD</li>
 *   <li>isAdmin() 체크 시 둘 다 확인</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final String ADMIN_SET_KEY = "auth:admins";

    private final RedissonClient redissonClient;

    @Value("${auth.admin.allowlist:}")
    private String bootstrapAllowlist;

    private Set<String> bootstrapAdmins;

    @PostConstruct
    public void init() {
        // Bootstrap admins from environment variable (immutable)
        this.bootstrapAdmins = parseAllowlist(bootstrapAllowlist);

        if (!bootstrapAdmins.isEmpty()) {
            log.info("Bootstrap admins loaded: {} entries", bootstrapAdmins.size());
        }
    }

    /**
     * fingerprint가 Admin인지 확인
     * Bootstrap allowlist 또는 Redis에 있으면 Admin
     */
    public boolean isAdmin(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }

        // 1. Bootstrap allowlist 확인 (환경변수)
        if (bootstrapAdmins.contains(fingerprint)) {
            return true;
        }

        // 2. Redis 확인 (런타임 추가된 Admin)
        return getAdminSet().contains(fingerprint);
    }

    /**
     * 새 Admin 추가 (Redis)
     */
    public void addAdmin(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new InvalidAdminFingerprintException();
        }

        getAdminSet().add(fingerprint);
        log.info("Admin added: fingerprint={}", StringMaskingUtils.maskFingerprintWithSuffix(fingerprint));
    }

    /**
     * Admin 제거 (Redis만, Bootstrap은 제거 불가)
     */
    public boolean removeAdmin(String fingerprint) {
        if (bootstrapAdmins.contains(fingerprint)) {
            log.warn("Cannot remove bootstrap admin: fingerprint={}", StringMaskingUtils.maskFingerprintWithSuffix(fingerprint));
            return false;
        }

        boolean removed = getAdminSet().remove(fingerprint);
        if (removed) {
            log.info("Admin removed: fingerprint={}", StringMaskingUtils.maskFingerprintWithSuffix(fingerprint));
        }
        return removed;
    }

    /**
     * 전체 Admin 목록 조회 (Bootstrap + Redis)
     */
    public Set<String> getAllAdmins() {
        Set<String> allAdmins = new java.util.HashSet<>(bootstrapAdmins);
        allAdmins.addAll(getAdminSet().readAll());
        return allAdmins;
    }

    /**
     * Redis Admin 수 조회
     */
    public int getRedisAdminCount() {
        return getAdminSet().size();
    }

    private RSet<String> getAdminSet() {
        return redissonClient.getSet(ADMIN_SET_KEY);
    }

    /**
     * Allowlist 파싱 (#271 V5 P1 Fix: Immutable Set 반환)
     *
     * <p>Collectors.toUnmodifiableSet()으로 불변 Set 반환하여
     * 외부에서 수정 불가하도록 보장합니다.</p>
     */
    private Set<String> parseAllowlist(String allowlist) {
        if (allowlist == null || allowlist.isBlank()) {
            return Set.of();
        }

        return Collections.unmodifiableSet(
                Arrays.stream(allowlist.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet())
        );
    }

}
