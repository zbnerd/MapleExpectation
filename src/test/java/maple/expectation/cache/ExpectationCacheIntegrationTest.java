package maple.expectation.cache;

import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.cache.RestrictedCacheManager;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #158: Expectation 캐시 통합 테스트
 *
 * <h4>검증 대상</h4>
 * <ul>
 *   <li>B3: L2 CacheManager에서 equipment 캐시 구조적 봉쇄</li>
 *   <li>B4: L2 타입 복원 검증 (LinkedHashMap 아닌 원본 타입)</li>
 *   <li>M2: Typed Serializer에 @class 메타데이터 없음</li>
 * </ul>
 */
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)  // CLAUDE.md Section 24: Redis 공유 상태 충돌 방지
class ExpectationCacheIntegrationTest extends IntegrationTestSupport {

    @Autowired
    @Qualifier("expectationL1CacheManager")
    private CacheManager l1CacheManager;

    @Autowired
    @Qualifier("expectationL2CacheManager")
    private CacheManager l2CacheManager;

    @Autowired
    @Qualifier("expectationCacheSerializer")
    private RedisSerializer<Object> expectationCacheSerializer;

    // ==================== B3: L2 equipment 캐시 구조적 봉쇄 ====================

    @Nested
    @DisplayName("B3: L2 CacheManager equipment 캐시 구조적 봉쇄")
    class B3_L2CacheManagerEquipmentBlock {

        @Test
        @DisplayName("L2 CacheManager에서 equipment 캐시 요청 시 null 반환")
        void l2CacheManager_shouldNotCreateEquipmentCache() {
            // when
            Cache equipmentCache = l2CacheManager.getCache("equipment");

            // then
            assertThat(equipmentCache).isNull();
        }

        @Test
        @DisplayName("L2 CacheManager에서 expectationResult만 허용")
        void l2CacheManager_shouldOnlyAllowExpectationResultCache() {
            // when: expectationResult는 허용
            Cache expectationCache = l2CacheManager.getCache("expectationResult");

            // then
            assertThat(expectationCache).isNotNull();

            // when: equipment는 차단
            Cache equipmentCache = l2CacheManager.getCache("equipment");

            // then
            assertThat(equipmentCache).isNull();

            // when: 임의의 다른 캐시도 차단
            Cache randomCache = l2CacheManager.getCache("someOtherCache");

            // then
            assertThat(randomCache).isNull();
        }

        @Test
        @DisplayName("L2 CacheManager getCacheNames는 expectationResult만 반환")
        void l2CacheManager_getCacheNames_shouldOnlyReturnExpectationResult() {
            // when
            var cacheNames = l2CacheManager.getCacheNames();

            // then
            assertThat(cacheNames).containsExactly("expectationResult");
        }

        @Test
        @DisplayName("L2 CacheManager는 RestrictedCacheManager 타입")
        void l2CacheManager_shouldBeRestrictedCacheManager() {
            // then
            assertThat(l2CacheManager).isInstanceOf(RestrictedCacheManager.class);
        }
    }

    // ==================== B4: L2 타입 복원 검증 ====================

    @Nested
    @DisplayName("B4: L2 타입 복원 검증")
    class B4_L2TypeRestoration {

        @Test
        @DisplayName("L2 캐시 get(key, clazz) - TotalExpectationResponse 타입 복원")
        void l2CacheGet_shouldReturnCorrectType_getWithClass() {
            // given
            Cache l2Cache = l2CacheManager.getCache("expectationResult");
            assertThat(l2Cache).isNotNull();

            TotalExpectationResponse original = createTestResponse();
            String testKey = "test-key-b4-1";

            // when
            l2Cache.put(testKey, original);
            TotalExpectationResponse typed = l2Cache.get(testKey, TotalExpectationResponse.class);

            // then
            assertThat(typed).isNotNull();
            assertThat(typed.getUserIgn()).isEqualTo(original.getUserIgn());
            assertThat(typed.getTotalCost()).isEqualTo(original.getTotalCost());
            assertThat(typed.getItems()).hasSize(original.getItems().size());

            // cleanup
            l2Cache.evict(testKey);
        }

        @Test
        @DisplayName("L2 캐시 wrapper.get() - LinkedHashMap이 아닌 TotalExpectationResponse 반환")
        void l2CacheGet_shouldReturnCorrectType_wrapperGet() {
            // given
            Cache l2Cache = l2CacheManager.getCache("expectationResult");
            assertThat(l2Cache).isNotNull();

            TotalExpectationResponse original = createTestResponse();
            String testKey = "test-key-b4-2";

            // when
            l2Cache.put(testKey, original);
            Cache.ValueWrapper wrapper = l2Cache.get(testKey);

            // then
            assertThat(wrapper).isNotNull();
            Object raw = wrapper.get();
            assertThat(raw).isNotNull();
            assertThat(raw).isInstanceOf(TotalExpectationResponse.class);
            assertThat(raw).isNotInstanceOf(java.util.LinkedHashMap.class);

            // cleanup
            l2Cache.evict(testKey);
        }
    }

    // ==================== M2: Typed Serializer @class 메타데이터 검증 ====================

    @Nested
    @DisplayName("M2: Typed Serializer @class 메타데이터 없음")
    class M2_TypedSerializerNoClassMetadata {

        @Test
        @DisplayName("Typed Serializer serialize 결과에 @class 메타데이터 없음")
        void typedSerializer_shouldNotIncludeClassMetadata() {
            // given
            TotalExpectationResponse response = createTestResponse();

            // when
            byte[] bytes = expectationCacheSerializer.serialize(response);

            // then
            assertThat(bytes).isNotNull();
            String json = new String(bytes, StandardCharsets.UTF_8);

            // @class 메타데이터가 없어야 함
            assertThat(json).doesNotContain("@class");
            assertThat(json).doesNotContain("java.util");
            assertThat(json).doesNotContain("java.lang");
        }

        @Test
        @DisplayName("Typed Serializer deserialize 후 정확한 타입 복원")
        void typedSerializer_shouldDeserializeToCorrectType() {
            // given
            TotalExpectationResponse original = createTestResponse();

            // when
            byte[] bytes = expectationCacheSerializer.serialize(original);
            Object deserialized = expectationCacheSerializer.deserialize(bytes);

            // then
            assertThat(deserialized).isNotNull();
            assertThat(deserialized).isInstanceOf(TotalExpectationResponse.class);

            TotalExpectationResponse restored = (TotalExpectationResponse) deserialized;
            assertThat(restored.getUserIgn()).isEqualTo(original.getUserIgn());
            assertThat(restored.getTotalCost()).isEqualTo(original.getTotalCost());
        }
    }

    // ==================== 헬퍼 메서드 ====================

    private TotalExpectationResponse createTestResponse() {
        return TotalExpectationResponse.builder()
                .userIgn("TestUser123")
                .totalCost(530000000000L)
                .totalCostText("5,300억")
                .items(List.of(
                        TotalExpectationResponse.ItemExpectation.builder()
                                .part("모자")
                                .itemName("에테르넬 나이트헬름")
                                .potential("STR 12% | 9% | 9%")
                                .expectedCost(80000000000L)
                                .expectedCostText("800억")
                                .expectedCount(1500L)
                                .build(),
                        TotalExpectationResponse.ItemExpectation.builder()
                                .part("상의")
                                .itemName("에테르넬 나이트아머")
                                .potential("STR 12% | 9% | 6%")
                                .expectedCost(120000000000L)
                                .expectedCostText("1,200억")
                                .expectedCount(2300L)
                                .build()
                ))
                .build();
    }
}
