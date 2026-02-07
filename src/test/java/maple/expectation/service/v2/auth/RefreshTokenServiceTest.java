package maple.expectation.service.v2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import maple.expectation.domain.RefreshToken;
import maple.expectation.global.error.exception.auth.InvalidRefreshTokenException;
import maple.expectation.global.error.exception.auth.RefreshTokenExpiredException;
import maple.expectation.global.error.exception.auth.TokenReusedException;
import maple.expectation.domain.repository.RedisRefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RefreshTokenService 단위 테스트 (Issue #279)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 Refresh Token 서비스를 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>Refresh Token 생성
 *   <li>Token Rotation (정상)
 *   <li>만료 토큰 검증
 *   <li>재사용 토큰 탈취 감지
 *   <li>Family 무효화
 * </ul>
 */
@Tag("unit")
class RefreshTokenServiceTest {

  private static final String SESSION_ID = "session-123";
  private static final String FINGERPRINT = "fingerprint-456";
  private static final String FAMILY_ID = "family-789";
  private static final String REFRESH_TOKEN_ID = "refresh-token-001";
  private static final long EXPIRATION_SECONDS = 604800L; // 7일

  private RedisRefreshTokenRepository refreshTokenRepository;
  private RefreshTokenService refreshTokenService;

  @BeforeEach
  void setUp() {
    refreshTokenRepository = mock(RedisRefreshTokenRepository.class);
    refreshTokenService = new RefreshTokenService(refreshTokenRepository);
    ReflectionTestUtils.setField(
        refreshTokenService, "refreshTokenExpirationSeconds", EXPIRATION_SECONDS);
  }

  @Nested
  @DisplayName("Refresh Token 생성")
  class CreateTest {

    @Test
    @DisplayName("새 Refresh Token 생성 성공")
    void shouldCreateRefreshTokenSuccessfully() {
      // when
      RefreshToken token = refreshTokenService.createRefreshToken(SESSION_ID, FINGERPRINT);

      // then
      assertThat(token).isNotNull();
      assertThat(token.sessionId()).isEqualTo(SESSION_ID);
      assertThat(token.fingerprint()).isEqualTo(FINGERPRINT);
      assertThat(token.familyId()).isNotNull();
      assertThat(token.used()).isFalse();
      assertThat(token.isExpired()).isFalse();

      verify(refreshTokenRepository).save(any(RefreshToken.class));
    }
  }

  @Nested
  @DisplayName("Token Rotation")
  class RotationTest {

    @Test
    @DisplayName("Token Rotation 성공 - 새 토큰 발급")
    void shouldRotateTokenSuccessfully() {
      // given
      RefreshToken oldToken = createValidToken(false);
      given(refreshTokenRepository.findById(REFRESH_TOKEN_ID)).willReturn(Optional.of(oldToken));

      // when
      RefreshToken newToken = refreshTokenService.rotateRefreshToken(REFRESH_TOKEN_ID);

      // then
      assertThat(newToken).isNotNull();
      assertThat(newToken.refreshTokenId()).isNotEqualTo(REFRESH_TOKEN_ID); // 새 ID
      assertThat(newToken.sessionId()).isEqualTo(SESSION_ID);
      assertThat(newToken.familyId()).isEqualTo(FAMILY_ID); // 동일 Family
      assertThat(newToken.used()).isFalse();

      // 기존 토큰 사용 처리 확인
      verify(refreshTokenRepository).markAsUsed(REFRESH_TOKEN_ID);
      // 새 토큰 저장 확인
      verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Token Rotation 실패 - 유효하지 않은 토큰")
    void shouldThrowWhenTokenNotFound() {
      // given
      given(refreshTokenRepository.findById("invalid-token")).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken("invalid-token"))
          .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("Token Rotation 실패 - 만료된 토큰")
    void shouldThrowWhenTokenExpired() {
      // given
      RefreshToken expiredToken = createExpiredToken();
      given(refreshTokenRepository.findById(REFRESH_TOKEN_ID))
          .willReturn(Optional.of(expiredToken));

      // when & then
      assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(REFRESH_TOKEN_ID))
          .isInstanceOf(RefreshTokenExpiredException.class);

      // 만료 토큰 삭제 확인
      verify(refreshTokenRepository).deleteById(REFRESH_TOKEN_ID);
    }

    @Test
    @DisplayName("Token Rotation 실패 - 이미 사용된 토큰 (탈취 감지)")
    void shouldThrowWhenTokenAlreadyUsed() {
      // given
      RefreshToken usedToken = createValidToken(true); // used=true
      given(refreshTokenRepository.findById(REFRESH_TOKEN_ID)).willReturn(Optional.of(usedToken));

      // when & then
      assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(REFRESH_TOKEN_ID))
          .isInstanceOf(TokenReusedException.class);

      // Family 전체 무효화 확인
      verify(refreshTokenRepository).deleteByFamilyId(FAMILY_ID);
    }
  }

  @Nested
  @DisplayName("Family 무효화")
  class FamilyInvalidationTest {

    @Test
    @DisplayName("Token Family 무효화 성공")
    void shouldInvalidateFamilySuccessfully() {
      // when
      refreshTokenService.invalidateFamily(FAMILY_ID);

      // then
      verify(refreshTokenRepository).deleteByFamilyId(FAMILY_ID);
    }
  }

  @Nested
  @DisplayName("세션별 삭제")
  class SessionDeletionTest {

    @Test
    @DisplayName("세션의 모든 Refresh Token 삭제 성공")
    void shouldDeleteBySessionIdSuccessfully() {
      // when
      refreshTokenService.deleteBySessionId(SESSION_ID);

      // then
      verify(refreshTokenRepository).deleteBySessionId(SESSION_ID);
    }
  }

  // ==================== Helper Methods ====================

  private RefreshToken createValidToken(boolean used) {
    return new RefreshToken(
        REFRESH_TOKEN_ID,
        SESSION_ID,
        FINGERPRINT,
        FAMILY_ID,
        Instant.now().plusSeconds(EXPIRATION_SECONDS), // 유효
        used);
  }

  private RefreshToken createExpiredToken() {
    return new RefreshToken(
        REFRESH_TOKEN_ID,
        SESSION_ID,
        FINGERPRINT,
        FAMILY_ID,
        Instant.now().minusSeconds(1), // 만료
        false);
  }
}
