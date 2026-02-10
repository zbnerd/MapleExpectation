package maple.expectation.service.v2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;
import maple.expectation.domain.Session;
import maple.expectation.domain.repository.RedisSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SessionService 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 세션 관리 서비스를 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>세션 생성 (UUID 생성, Redis 저장)
 *   <li>세션 조회 및 TTL 갱신 (Sliding Window)
 *   <li>세션 조회 (TTL 갱신 없음)
 *   <li>세션 삭제 (로그아웃)
 *   <li>세션 존재 여부 확인
 * </ul>
 */
@Tag("unit")
class SessionServiceTest {

  private static final String SESSION_ID = "session-123";
  private static final String FINGERPRINT = "fp-abc";
  private static final String API_KEY = "test-api-key";
  private static final Set<String> MY_OCIDS = Set.of("ocid1", "ocid2");
  private static final String ROLE_USER = "USER";
  private static final String ROLE_ADMIN = "ADMIN";

  private RedisSessionRepository sessionRepository;
  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    sessionRepository = mock(RedisSessionRepository.class);
    sessionService = new SessionService(sessionRepository);
  }

  @Nested
  @DisplayName("세션 생성")
  class CreateSessionTest {

    @Test
    @DisplayName("USER 세션 생성 성공")
    void shouldCreateUserSession() {
      // when
      Session session =
          sessionService.createSession(
              FINGERPRINT, "TestUser", "test-account-id", API_KEY, MY_OCIDS, ROLE_USER);

      // then
      assertThat(session.sessionId()).isNotBlank();
      assertThat(session.fingerprint()).isEqualTo(FINGERPRINT);
      assertThat(session.apiKey()).isEqualTo(API_KEY);
      assertThat(session.myOcids()).isEqualTo(MY_OCIDS);
      assertThat(session.role()).isEqualTo(ROLE_USER);

      verify(sessionRepository)
          .save(argThat(s -> s.fingerprint().equals(FINGERPRINT) && s.role().equals(ROLE_USER)));
    }

    @Test
    @DisplayName("ADMIN 세션 생성 성공")
    void shouldCreateAdminSession() {
      // when
      Session session =
          sessionService.createSession(
              FINGERPRINT, "TestUser", "test-account-id", API_KEY, MY_OCIDS, ROLE_ADMIN);

      // then
      assertThat(session.role()).isEqualTo(ROLE_ADMIN);
      verify(sessionRepository).save(any(Session.class));
    }

    @Test
    @DisplayName("세션 ID는 UUID 형식")
    void shouldGenerateUuidSessionId() {
      // when
      Session session =
          sessionService.createSession(
              FINGERPRINT, "TestUser", "test-account-id", API_KEY, MY_OCIDS, ROLE_USER);

      // then
      assertThat(session.sessionId())
          .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("매 호출마다 다른 세션 ID 생성")
    void shouldGenerateUniqueSessionId() {
      // when
      Session session1 =
          sessionService.createSession(
              FINGERPRINT, "TestUser", "test-account-id", API_KEY, MY_OCIDS, ROLE_USER);
      Session session2 =
          sessionService.createSession(
              FINGERPRINT, "TestUser", "test-account-id", API_KEY, MY_OCIDS, ROLE_USER);

      // then
      assertThat(session1.sessionId()).isNotEqualTo(session2.sessionId());
    }
  }

  @Nested
  @DisplayName("세션 조회 및 TTL 갱신")
  class GetSessionAndRefreshTest {

    @Test
    @DisplayName("세션 존재 시 TTL 갱신")
    void shouldRefreshTtlWhenSessionExists() {
      // given
      Session existingSession =
          Session.create(
              SESSION_ID, FINGERPRINT, "TestUser", "test-account-id", API_KEY, MY_OCIDS, ROLE_USER);
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(existingSession));

      // when
      Optional<Session> result = sessionService.getSessionAndRefresh(SESSION_ID);

      // then
      assertThat(result).isPresent();
      assertThat(result.get()).isEqualTo(existingSession);
      verify(sessionRepository).refreshTtl(SESSION_ID);
    }

    @Test
    @DisplayName("세션 없음 시 TTL 갱신 안함")
    void shouldNotRefreshTtlWhenSessionNotFound() {
      // given
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

      // when
      Optional<Session> result = sessionService.getSessionAndRefresh(SESSION_ID);

      // then
      assertThat(result).isEmpty();
      verify(sessionRepository, never()).refreshTtl(anyString());
    }
  }

  @Nested
  @DisplayName("세션 조회 (TTL 갱신 없음)")
  class GetSessionTest {

    @Test
    @DisplayName("세션 존재 시 반환 (TTL 갱신 없음)")
    void shouldReturnSessionWithoutRefresh() {
      // given
      Session existingSession =
          Session.create(
              SESSION_ID, FINGERPRINT, "TestUser", "test-account-id", API_KEY, MY_OCIDS, ROLE_USER);
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(existingSession));

      // when
      Optional<Session> result = sessionService.getSession(SESSION_ID);

      // then
      assertThat(result).isPresent();
      verify(sessionRepository, never()).refreshTtl(anyString());
    }

    @Test
    @DisplayName("세션 없음 시 Optional.empty 반환")
    void shouldReturnEmptyWhenNotFound() {
      // given
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

      // when
      Optional<Session> result = sessionService.getSession(SESSION_ID);

      // then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("세션 삭제")
  class DeleteSessionTest {

    @Test
    @DisplayName("세션 삭제 성공")
    void shouldDeleteSession() {
      // when
      sessionService.deleteSession(SESSION_ID);

      // then
      verify(sessionRepository).deleteById(SESSION_ID);
    }
  }

  @Nested
  @DisplayName("세션 존재 여부 확인")
  class ExistsSessionTest {

    @Test
    @DisplayName("세션 존재 시 true")
    void shouldReturnTrueWhenExists() {
      // given
      given(sessionRepository.existsById(SESSION_ID)).willReturn(true);

      // when
      boolean exists = sessionService.existsSession(SESSION_ID);

      // then
      assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("세션 없음 시 false")
    void shouldReturnFalseWhenNotExists() {
      // given
      given(sessionRepository.existsById(SESSION_ID)).willReturn(false);

      // when
      boolean exists = sessionService.existsSession(SESSION_ID);

      // then
      assertThat(exists).isFalse();
    }
  }
}
