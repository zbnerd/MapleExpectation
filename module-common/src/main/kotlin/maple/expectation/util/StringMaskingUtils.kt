@file:JvmName("StringMaskingUtils")

package maple.expectation.util

/**
 * 로그 출력 시 개인정보(OCID, Fingerprint, AccountId 등)를 마스킹하는 유틸리티
 *
 * <p>각 서비스에 분산되어 있던 private maskXxx() 메서드를 통합하여 일관된 마스킹 정책을 적용합니다.
 */
object StringMaskingUtils {

    private const val OCID_MASK = "***"
    private const val FINGERPRINT_MASK = "****"
    private const val MIN_LENGTH = 8
    private const val PREFIX_LENGTH = 4
    private const val CACHE_KEY_PATTERN = "(expectation:v\\d+):[^:]+"
    private const val CACHE_KEY_REPLACEMENT = "$1***"

    /**
     * OCID 마스킹: 앞 4자리 + "***"
     *
     * @param value OCID 문자열
     * @return 마스킹된 문자열 (null/짧은 경우 "***")
     */
    @JvmStatic
    fun maskOcid(value: String?): String {
        if (value == null || value.length < MIN_LENGTH) return OCID_MASK
        return value.substring(0, PREFIX_LENGTH) + OCID_MASK
    }

    /**
     * Fingerprint 마스킹: 앞 4자리 + "****"
     *
     * @param fingerprint Fingerprint 문자열
     * @return 마스킹된 문자열 (null/짧은 경우 "****")
     */
    @JvmStatic
    fun maskFingerprint(fingerprint: String?): String {
        if (fingerprint == null || fingerprint.length < MIN_LENGTH) return FINGERPRINT_MASK
        return fingerprint.substring(0, PREFIX_LENGTH) + FINGERPRINT_MASK
    }

    /**
     * Fingerprint 마스킹 (앞 4자리 + "****" + 뒤 4자리)
     *
     * <p>AdminService, AdminController에서 사용하는 샌드위치 패턴
     *
     * @param fingerprint Fingerprint 문자열
     * @return 마스킹된 문자열 (null/짧은 경우 "****")
     */
    @JvmStatic
    fun maskFingerprintWithSuffix(fingerprint: String?): String {
        if (fingerprint == null || fingerprint.length < MIN_LENGTH) return FINGERPRINT_MASK
        return fingerprint.substring(0, PREFIX_LENGTH) +
                FINGERPRINT_MASK +
                fingerprint.substring(fingerprint.length - PREFIX_LENGTH)
    }

    /**
     * 캐시 키 마스킹: 정규식 기반 OCID 부분 치환
     *
     * <p>"expectation:v3:ocid123:..." → "expectation:v3:***:..."
     *
     * @param key 캐시 키 문자열
     * @return 마스킹된 문자열 (null인 경우 "null")
     */
    @JvmStatic
    fun maskCacheKey(key: String?): String {
        if (key == null) return "null"
        return key.replace(CACHE_KEY_PATTERN.toRegex(), CACHE_KEY_REPLACEMENT)
    }

    /**
     * AccountId 마스킹: 안전한 서브스트링 (최대 8자리)
     *
     * <p>StringIndexOutOfBoundsException 방지를 위해 Math.min 사용
     *
     * @param accountId 계정 ID 문자열
     * @return 마스킹된 문자열 (null/빈 문자열인 경우 "***")
     */
    @JvmStatic
    fun maskAccountId(accountId: String?): String {
        if (accountId.isNullOrEmpty()) return OCID_MASK
        return accountId.substring(0, minOf(MIN_LENGTH, accountId.length)) + "..."
    }
}
