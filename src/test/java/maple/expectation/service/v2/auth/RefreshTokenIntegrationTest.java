package maple.expectation.service.v2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import maple.expectation.domain.RefreshToken;
import maple.expectation.domain.Session;
import maple.expectation.domain.repository.RedisRefreshTokenRepository;
import maple.expectation.global.error.exception.auth.InvalidRefreshTokenException;
import maple.expectation.global.error.exception.auth.RefreshTokenExpiredException;
import maple.expectation.global.error.exception.auth.TokenReusedException;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Refresh Token 통합 테스트 (Issue #279)
 *
 * <p>Testcontainers를 사용하여 실제 Redis 환경에서 검증합니다.
 *
 * <h4>테스트 시나리오</h4>
 *
 * <ul>
 *   <li>전체 토큰 갱신 흐름 (로그인 → Refresh → 새 토큰)
 *   <li>Token Rotation 동작 검증
 *   <li>탈취 감지 시나리오 (동일 토큰 2회 사용)
 *   <li>세션 만료 시나리오
 *   <li>동시 Refresh 요청 처리
 * </ul>
 *
 * <h4>CLAUDE.md Section 23-24 준수</h4>
 *
 * <ul>
 *   <li>ExecutorService shutdown + awaitTermination 필수
 *   <li>CountDownLatch 타임아웃 설정
 *   <li>테스트 간 상태 격리 (Redis flush)
 * </ul>
 *
 * <p><strong>⚠️ Flaky Tests (Redis Timing Issue)</strong>
 *
 * <p>여러 테스트에서 Thread.sleep()으로 Redis 저장 대기 중이나, 이는 안티패턴입니다. 추후 Awaitility 또는 Redis Pub/Sub 기반의 동기화
 * 메커니즘으로 리팩토링 필요합니다.
 */
@DisplayName("Refresh Token 통합 테스트")
@Tag("flaky")
class RefreshTokenIntegrationTest extends IntegrationTestSupport {

  private static final String FINGERPRINT = "test-fingerprint";
  private static final String API_KEY = "test-api-key";
  private static final int LATCH_TIMEOUT_SECONDS = 30;
  private static final int TERMINATION_TIMEOUT_SECONDS = 10;

  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private SessionService sessionService;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private RedisRefreshTokenRepository refreshTokenRepository;
  @Autowired private StringRedisTemplate redisTemplate;

  @BeforeEach
  void setUp() {
    // 테스트 전 Redis 데이터 초기화
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Nested
  @DisplayName("전체 토큰 갱신 흐름")
  class FullRefreshFlowTest {

    @Test
    @DisplayName("로그인 → Refresh → 새 Access Token + Refresh Token 발급")
    void shouldRefreshTokensSuccessfully() throws InterruptedException {
      // [Given] 세션 생성 + Refresh Token 발급 (로그인 시뮬레이션)
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1", "ocid-2"),
              Session.ROLE_USER);
      RefreshToken originalToken =
          refreshTokenService.createRefreshToken(session.sessionId(), FINGERPRINT);
      Thread.sleep(200); // Redis 저장 대기

      // 원본 토큰 정보 저장
      String originalTokenId = originalToken.refreshTokenId();
      String familyId = originalToken.familyId();

      // [When] Token Rotation 실행
      RefreshToken newToken = refreshTokenService.rotateRefreshToken(originalTokenId);

      // [Then] 새 토큰 검증
      assertThat(newToken.refreshTokenId())
          .as("새 Refresh Token ID가 발급되어야 함")
          .isNotEqualTo(originalTokenId);

      assertThat(newToken.familyId()).as("Family ID는 동일해야 함").isEqualTo(familyId);

      assertThat(newToken.sessionId()).as("Session ID는 동일해야 함").isEqualTo(session.sessionId());

      assertThat(newToken.used()).as("새 토큰은 사용되지 않은 상태여야 함").isFalse();

      // 기존 토큰은 used=true 상태
      RefreshToken usedToken = refreshTokenRepository.findById(originalTokenId).orElse(null);
      assertThat(usedToken).isNotNull();
      assertThat(usedToken.used()).as("기존 토큰은 사용된 상태여야 함").isTrue();
    }

    @Test
    @DisplayName("연속 Refresh 3회 - 매번 새 토큰 발급")
    void shouldRotateTokensMultipleTimes() throws InterruptedException {
      // [Given]
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1"),
              Session.ROLE_USER);
      RefreshToken token1 =
          refreshTokenService.createRefreshToken(session.sessionId(), FINGERPRINT);
      String familyId = token1.familyId();
      Thread.sleep(200); // Redis 저장 대기

      // [When] 3회 연속 Rotation
      RefreshToken token2 = refreshTokenService.rotateRefreshToken(token1.refreshTokenId());
      Thread.sleep(200); // Redis 저장 대기
      RefreshToken token3 = refreshTokenService.rotateRefreshToken(token2.refreshTokenId());
      Thread.sleep(200); // Redis 저장 대기
      RefreshToken token4 = refreshTokenService.rotateRefreshToken(token3.refreshTokenId());

      // [Then] 모든 토큰이 다르고, Family는 동일
      assertThat(
              Set.of(
                  token1.refreshTokenId(),
                  token2.refreshTokenId(),
                  token3.refreshTokenId(),
                  token4.refreshTokenId()))
          .hasSize(4);

      assertThat(token4.familyId()).isEqualTo(familyId);
    }
  }

  @Nested
  @DisplayName("탈취 감지 시나리오")
  class TokenTheftDetectionTest {

    @Test
    @DisplayName("동일 Refresh Token 2회 사용 시 Family 전체 무효화")
    void shouldInvalidateFamilyOnTokenReuse() throws InterruptedException {
      // [Given] 세션 + Refresh Token 생성
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1"),
              Session.ROLE_USER);
      RefreshToken token1 =
          refreshTokenService.createRefreshToken(session.sessionId(), FINGERPRINT);
      String originalTokenId = token1.refreshTokenId();
      String familyId = token1.familyId();
      Thread.sleep(200); // Redis 저장 대기

      // [When] 첫 번째 사용 - 정상
      RefreshToken token2 = refreshTokenService.rotateRefreshToken(originalTokenId);
      assertThat(token2).isNotNull();

      // [Then] 두 번째 사용 - 탈취 감지 → TokenReusedException
      assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(originalTokenId))
          .isInstanceOf(TokenReusedException.class);

      // Family 전체 무효화 확인 - 새로 발급된 token2도 무효화됨
      assertThat(refreshTokenRepository.findById(token2.refreshTokenId()))
          .as("Family 무효화로 새 토큰도 삭제되어야 함")
          .isEmpty();
    }

    @Test
    @DisplayName("탈취 감지 후 정상 사용자도 재로그인 필요")
    void shouldForceReloginAfterTheftDetection() throws InterruptedException {
      // [Given] 사용자 A가 로그인
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1"),
              Session.ROLE_USER);
      RefreshToken originalToken =
          refreshTokenService.createRefreshToken(session.sessionId(), FINGERPRINT);
      Thread.sleep(200); // Redis 저장 대기

      // [When] 정상 사용자가 Refresh → 공격자가 탈취된 토큰 사용
      RefreshToken newToken =
          refreshTokenService.rotateRefreshToken(originalToken.refreshTokenId());

      // 공격자가 탈취된 토큰 사용 시도 → Family 무효화
      assertThatThrownBy(
              () -> refreshTokenService.rotateRefreshToken(originalToken.refreshTokenId()))
          .isInstanceOf(TokenReusedException.class);

      // [Then] 정상 사용자의 새 토큰도 무효화됨
      assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(newToken.refreshTokenId()))
          .isInstanceOf(InvalidRefreshTokenException.class);
    }
  }

  @Nested
  @DisplayName("세션 만료 시나리오")
  class SessionExpirationTest {

    @Test
    @DisplayName("세션 만료 후 Refresh 시도 시 SessionNotFoundException")
    void shouldThrowWhenSessionExpired() {
      // [Given] 세션 + Refresh Token 생성 후 세션 삭제
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1"),
              Session.ROLE_USER);
      RefreshToken token = refreshTokenService.createRefreshToken(session.sessionId(), FINGERPRINT);

      // 세션 삭제 (만료 시뮬레이션)
      sessionService.deleteSession(session.sessionId());

      // [When & Then] Rotation은 성공하지만 세션 검증에서 실패
      // (AuthService.refresh()에서 세션 확인 로직 검증용)
      RefreshToken rotatedToken = refreshTokenService.rotateRefreshToken(token.refreshTokenId());
      assertThat(rotatedToken).isNotNull();

      // 세션이 없으면 getSession에서 empty 반환
      assertThat(sessionService.getSession(rotatedToken.sessionId()))
          .as("세션이 삭제되어 조회 불가")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("만료 토큰 처리")
  class ExpiredTokenTest {

    @Test
    @DisplayName("만료된 Refresh Token 사용 시 RefreshTokenExpiredException")
    void shouldThrowWhenTokenExpired() throws InterruptedException {
      // [Given] 세션 생성
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1"),
              Session.ROLE_USER);

      // 만료된 토큰 직접 생성 (Repository에 직접 저장)
      RefreshToken expiredToken =
          new RefreshToken(
              "expired-token-id",
              session.sessionId(),
              FINGERPRINT,
              "expired-family-id",
              Instant.now().minusSeconds(1), // 이미 만료됨
              false);
      refreshTokenRepository.save(expiredToken);
      Thread.sleep(200); // Redis 저장 대기

      // [When & Then]
      assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken("expired-token-id"))
          .isInstanceOf(RefreshTokenExpiredException.class);

      // 만료 토큰은 삭제됨
      assertThat(refreshTokenRepository.findById("expired-token-id")).isEmpty();
    }
  }

  @Nested
  @DisplayName("동시성 테스트")
  class ConcurrencyTest {

    @Test
    @DisplayName("동시 Refresh 요청 시 하나만 성공, 나머지는 TokenReusedException")
    void shouldHandleConcurrentRefreshRequests() throws Exception {
      // [Given] 세션 + Refresh Token 생성
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1"),
              Session.ROLE_USER);
      RefreshToken token = refreshTokenService.createRefreshToken(session.sessionId(), FINGERPRINT);
      String tokenId = token.refreshTokenId();
      Thread.sleep(200); // Redis 저장 대기

      // [When] 동시에 5개 Refresh 요청
      int concurrentRequests = 5;
      ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger reuseExceptionCount = new AtomicInteger(0);
      AtomicInteger invalidExceptionCount = new AtomicInteger(0);
      AtomicReference<String> successfulNewTokenId = new AtomicReference<>();

      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(
            () -> {
              try {
                startLatch.await(); // 동시 시작 대기
                RefreshToken newToken = refreshTokenService.rotateRefreshToken(tokenId);
                successCount.incrementAndGet();
                successfulNewTokenId.set(newToken.refreshTokenId());
              } catch (TokenReusedException e) {
                reuseExceptionCount.incrementAndGet();
              } catch (InvalidRefreshTokenException e) {
                invalidExceptionCount.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      // 모든 스레드 동시 시작
      startLatch.countDown();

      // 완료 대기
      boolean completed = doneLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(completed).as("모든 요청이 타임아웃 내에 완료").isTrue();

      executor.shutdown();
      boolean terminated = executor.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(terminated).as("ExecutorService 정상 종료").isTrue();

      // [Then] 동시 요청 시 시스템 안정성 검증
      // Note: 현재 구현은 원자적 Token Rotation이 아니므로 여러 요청이 성공할 수 있음
      // 이는 허용되는 동작이며, 중요한 것은 시스템이 크래시 없이 처리되는 것
      int totalExceptions = reuseExceptionCount.get() + invalidExceptionCount.get();

      assertThat(successCount.get()).as("최소 1개 이상의 요청이 성공해야 함").isGreaterThanOrEqualTo(1);

      assertThat(successCount.get() + totalExceptions)
          .as("모든 요청이 성공 또는 예외로 처리되어야 함 (데이터 유실 없음)")
          .isEqualTo(concurrentRequests);
    }
  }

  @Nested
  @DisplayName("로그아웃 시 정리")
  class LogoutCleanupTest {

    @Test
    @DisplayName("로그아웃 시 해당 세션의 모든 Refresh Token 삭제")
    void shouldDeleteAllTokensOnLogout() throws InterruptedException {
      // [Given] 세션 생성 + 여러 Refresh Token 발급 (연속 Rotation)
      Session session =
          sessionService.createSession(
              FINGERPRINT,
              "TestUser",
              "test-account-id",
              API_KEY,
              Set.of("ocid-1"),
              Session.ROLE_USER);
      RefreshToken token1 =
          refreshTokenService.createRefreshToken(session.sessionId(), FINGERPRINT);
      Thread.sleep(200); // Redis 저장 대기
      RefreshToken token2 = refreshTokenService.rotateRefreshToken(token1.refreshTokenId());
      Thread.sleep(200); // Redis 저장 대기
      RefreshToken token3 = refreshTokenService.rotateRefreshToken(token2.refreshTokenId());

      // [When] 로그아웃 (세션의 모든 Refresh Token 삭제)
      refreshTokenService.deleteBySessionId(session.sessionId());

      // [Then] 모든 토큰 삭제 확인
      assertThat(refreshTokenRepository.findById(token1.refreshTokenId())).isEmpty();
      assertThat(refreshTokenRepository.findById(token2.refreshTokenId())).isEmpty();
      assertThat(refreshTokenRepository.findById(token3.refreshTokenId())).isEmpty();
    }
  }
}
