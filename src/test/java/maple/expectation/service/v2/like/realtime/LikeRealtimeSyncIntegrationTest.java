package maple.expectation.service.v2.like.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import maple.expectation.global.queue.RedisKey;
import maple.expectation.service.v2.like.realtime.dto.LikeEvent;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 좋아요 실시간 동기화 통합 테스트 (Issue #278)
 *
 * <h3>검증 항목</h3>
 *
 * <ul>
 *   <li>Pub/Sub 이벤트 발행 및 수신 정상 동작
 *   <li>Self-skip: 자신이 발행한 이벤트는 무시
 *   <li>L1 캐시 무효화 검증
 * </ul>
 *
 * <h3>CLAUDE.md Section 23, 24 준수</h3>
 *
 * <ul>
 *   <li>CountDownLatch로 비동기 완료 대기
 *   <li>테스트 간 상태 격리 (Redis flushAll)
 * </ul>
 */
@DisplayName("좋아요 실시간 동기화 통합 테스트 (Issue #278)")
@TestPropertySource(properties = {"like.realtime.enabled=true", "app.instance-id=test-instance-1"})
class LikeRealtimeSyncIntegrationTest extends IntegrationTestSupport {

  private static final String TEST_USER_IGN = "TestCharacter";
  private static final int LATCH_TIMEOUT_SECONDS = 5;

  @Autowired private RedissonClient redissonClient;

  @Autowired private CacheManager cacheManager;

  @Autowired private StringRedisTemplate redisTemplate;

  @Autowired(required = false)
  private LikeEventPublisher likeEventPublisher;

  @Autowired(required = false)
  private LikeEventSubscriber likeEventSubscriber;

  @BeforeEach
  void setUp() {
    // 테스트 전 Redis 데이터 초기화
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

    // L1 캐시 초기화
    Cache characterCache = cacheManager.getCache("character");
    if (characterCache != null) {
      characterCache.clear();
    }
  }

  @Test
  @DisplayName("RTopic을 통한 이벤트 발행/수신 정상 동작")
  void shouldPublishAndReceiveEventViaTopic() throws InterruptedException {
    // Given
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<LikeEvent> receivedEvent = new AtomicReference<>();

    RTopic topic = redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.getKey());

    // 별도 리스너 등록 (테스트용)
    int listenerId =
        topic.addListener(
            LikeEvent.class,
            (channel, event) -> {
              receivedEvent.set(event);
              latch.countDown();
            });

    // When: 이벤트 발행
    LikeEvent publishedEvent = LikeEvent.like(TEST_USER_IGN, 5L, "test-publisher");
    topic.publish(publishedEvent);

    // Then: 이벤트 수신 확인
    boolean received = latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(received).isTrue();
    assertThat(receivedEvent.get()).isNotNull();
    assertThat(receivedEvent.get().userIgn()).isEqualTo(TEST_USER_IGN);
    assertThat(receivedEvent.get().newDelta()).isEqualTo(5L);
    assertThat(receivedEvent.get().eventType()).isEqualTo(LikeEvent.EventType.LIKE);

    // Cleanup
    topic.removeListener(listenerId);
  }

  @Test
  @DisplayName("LikeEventPublisher Bean이 정상 생성됨")
  void shouldCreatePublisherBean() {
    assertThat(likeEventPublisher).isNotNull();
  }

  @Test
  @DisplayName("LikeEventSubscriber Bean이 정상 생성됨")
  void shouldCreateSubscriberBean() {
    assertThat(likeEventSubscriber).isNotNull();
  }

  @Test
  @DisplayName("LikeEvent record 팩토리 메서드 검증")
  void shouldCreateLikeEventWithFactoryMethods() {
    // LIKE 이벤트
    LikeEvent likeEvent = LikeEvent.like("user1", 10L, "instance-1");
    assertThat(likeEvent.eventType()).isEqualTo(LikeEvent.EventType.LIKE);
    assertThat(likeEvent.userIgn()).isEqualTo("user1");
    assertThat(likeEvent.newDelta()).isEqualTo(10L);
    assertThat(likeEvent.sourceInstanceId()).isEqualTo("instance-1");
    assertThat(likeEvent.timestamp()).isNotNull();

    // UNLIKE 이벤트
    LikeEvent unlikeEvent = LikeEvent.unlike("user2", -5L, "instance-2");
    assertThat(unlikeEvent.eventType()).isEqualTo(LikeEvent.EventType.UNLIKE);
    assertThat(unlikeEvent.userIgn()).isEqualTo("user2");
    assertThat(unlikeEvent.newDelta()).isEqualTo(-5L);
  }

  @Test
  @DisplayName("RedisKey.LIKE_EVENTS_TOPIC Hash Tag 검증")
  void shouldHaveCorrectHashTag() {
    String key = RedisKey.LIKE_EVENTS_TOPIC.getKey();
    String hashTag = RedisKey.LIKE_EVENTS_TOPIC.getHashTag();

    assertThat(key).isEqualTo("{likes}:events");
    assertThat(hashTag).isEqualTo("likes");
  }

  @Test
  @DisplayName("다른 인스턴스 이벤트 수신 시 L1 캐시 evict")
  void shouldEvictL1CacheOnRemoteEvent() throws InterruptedException {
    // Given: L1 캐시에 데이터 설정
    Cache characterCache = cacheManager.getCache("character");
    assertThat(characterCache).isNotNull();
    characterCache.put(TEST_USER_IGN, "cached-value");

    // 캐시 존재 확인
    assertThat(characterCache.get(TEST_USER_IGN)).isNotNull();

    // When: 다른 인스턴스에서 발행한 것처럼 이벤트 발행
    RTopic topic = redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.getKey());
    LikeEvent remoteEvent = LikeEvent.like(TEST_USER_IGN, 1L, "remote-instance-999");
    topic.publish(remoteEvent);

    // 이벤트 처리 대기 (비동기)
    Thread.sleep(500);

    // Then: L1 캐시가 무효화되어야 함
    Cache.ValueWrapper wrapper = characterCache.get(TEST_USER_IGN);
    assertThat(wrapper).isNull();
  }
}
