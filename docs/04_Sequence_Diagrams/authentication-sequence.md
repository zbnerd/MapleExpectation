# Authentication Sequence

## 개요

사용자가 API Key와 IGN으로 인증하여 JWT Access Token과 Refresh Token을 발급받는 시퀀스입니다.

## 비즈니스 흐름

1. **로그인**: API Key 인증 → JWT + Refresh Token 발급
2. **토큰 갱신**: Refresh Token → 새 Access Token + Refresh Token 발급 (Token Rotation)
3. **로그아웃**: 세션 무효화 + Refresh Token 삭제

## 시퀀스 다이어그램

### 1. Login Flow

```mermaid
sequenceDiagram
    autonumber

    actor User as 사용자
    participant AuthCtrl as AuthController
    participant AuthService as AuthService
    participant MemberRepo as MemberRepository
    participant JWT as JwtProvider
    participant RefreshToken as RefreshTokenRepository
    participant Redis as Redis (Session)
    participant NexonAPI as NexonOpenAPI

    User->>AuthCtrl: POST /auth/login {apiKey, userIgn}
    activate AuthCtrl

    AuthCtrl->>AuthService: login(request)
    activate AuthService

    AuthService->>MemberRepo: findByApiKey(apiKey)
    MemberRepo-->>AuthService: Optional<Member>

    alt API Key 미등록
        AuthService-->>AuthCtrl: throw InvalidApiKeyException
        AuthCtrl-->>User: 401 Unauthorized
    end

    AuthService->>NexonAPI: getCharacterOcid(userIgn)
    NexonAPI-->>AuthService: ocid

    alt 캐릭터 존재하지 않음
        AuthService-->>AuthCtrl: throw CharacterNotFoundException
        AuthCtrl-->>User: 404 Not Found
    end

    Note over AuthService,JWT: JWT Access Token 발급

    AuthService->>JWT: generateToken(sessionId, role)
    JWT-->>AuthService: accessToken + expiresIn

    Note over AuthService,RefreshToken: Refresh Token 발급

    AuthService->>RefreshToken: save(sessionId, token, 30일)
    RefreshToken-->>AuthService: saved

    Note over AuthService,Redis: 세션 정보 저장 (Redis)

    AuthService->>Redis: setex(SESSION:{sessionId}, 24h, sessionData)
    Redis-->>AuthService: OK

    AuthService-->>AuthCtrl: LoginResponse(accessToken, refreshToken, ...)
    deactivate AuthService

    AuthCtrl-->>User: 200 OK {accessToken, refreshToken, expiresIn}
    deactivate AuthCtrl
```

### 2. Token Refresh Flow (Issue #279)

```mermaid
sequenceDiagram
    autonumber

    actor User as 사용자
    participant AuthCtrl as AuthController
    participant AuthService as AuthService
    participant RefreshToken as RefreshTokenRepository
    participant JWT as JwtProvider
    participant Redis as Redis (Session)

    User->>AuthCtrl: POST /auth/refresh {refreshToken}
    activate AuthCtrl

    AuthCtrl->>AuthService: refresh(refreshToken)
    activate AuthService

    AuthService->>RefreshToken: findByToken(refreshToken)
    RefreshToken-->>AuthService: Optional<RefreshToken>

    alt Refresh Token 미등록/만료
        AuthService-->>AuthCtrl: throw InvalidRefreshTokenException
        AuthCtrl-->>User: 401 Unauthorized
    end

    Note over AuthService,RefreshToken: Token Rotation (기존 토큰 무효화)

    AuthService->>RefreshToken: deleteByToken(refreshToken)
    RefreshToken-->>AuthService: deleted

    AuthService->>JWT: generateToken(sessionId, role)
    JWT-->>AuthService: newAccessToken + accessExpiresIn

    AuthService->>RefreshToken: save(sessionId, newRefreshToken, 30일)
    RefreshToken-->>AuthService: saved

    AuthService-->>AuthCtrl: TokenResponse(newAccessToken, newRefreshToken, ...)
    deactivate AuthService

    AuthCtrl-->>User: 200 OK {accessToken, refreshToken, expiresIn}
    deactivate AuthCtrl
```

### 3. Logout Flow

```mermaid
sequenceDiagram
    autonumber

    actor User as 사용자
    participant AuthCtrl as AuthController
    participant Security as SecurityFilter
    participant AuthService as AuthService
    participant Redis as Redis (Session)
    participant RefreshToken as RefreshTokenRepository

    User->>AuthCtrl: DELETE /auth/logout (Authorization: Bearer {accessToken})
    activate AuthCtrl

    Note over User,Security: JWT 인증 (Spring Security)

    Security->>Redis: get(SESSION:{sessionId})
    Redis-->>Security: sessionData
    Security->>AuthCtrl: @AuthenticationPrincipal AuthenticatedUser
    activate AuthCtrl

    AuthCtrl->>AuthService: logout(sessionId)
    activate AuthService

    Note over AuthService,Redis: 세션 무효화

    AuthService->>Redis: del(SESSION:{sessionId})
    Redis-->>AuthService: deleted

    Note over AuthService,RefreshToken: Refresh Token 삭제

    AuthService->>RefreshToken: deleteBySessionId(sessionId)
    RefreshToken-->>AuthService: deleted

    AuthService-->>AuthCtrl: void
    deactivate AuthService

    AuthCtrl-->>User: 200 OK
    deactivate AuthCtrl
```

## 관련 컴포넌트

| 컴포넌트 | 경로 | 역할 |
|---------|------|------|
| AuthController | `controller/AuthController.java` | 인증 API 엔드포인트 |
| AuthService | `service/v2/auth/AuthService.java` | 인증 비즈니스 로직 |
| JwtProvider | `infrastructure/security/JwtProvider.java` | JWT 생성/검증 |
| RefreshTokenRepository | `domain/redis/RefreshTokenRepository.java` | Refresh Token 저장소 |
| MemberRepository | `domain/member/MemberRepository.java` | 회원 정보 저장소 |

## 핵심 로직

### 1. 로그인 인증 흐름
```java
// 1. API Key 검증
Member member = memberRepository.findByApiKey(apiKey)
    .orElseThrow(() -> new InvalidApiKeyException(apiKey));

// 2. 캐릭터 존재 확인 (Nexon API)
String ocid = nexonApiClient.getCharacterOcid(userIgn);

// 3. JWT + Refresh Token 발급
String accessToken = jwtProvider.generateToken(sessionId, role);
refreshTokenRepository.save(sessionId, refreshToken, 30 days);
```

### 2. Token Rotation (Issue #279)
```java
// 기존 Refresh Token 무효화
refreshTokenRepository.deleteByToken(oldRefreshToken);

// 새 토큰 쌍 발급
String newAccessToken = jwtProvider.generateToken(sessionId, role);
String newRefreshToken = UUID.randomUUID().toString();
refreshTokenRepository.save(sessionId, newRefreshToken, 30 days);
```

### 3. 세션 무효화
```java
// Redis 세션 삭제
redisTemplate.delete("SESSION:" + sessionId);

// Refresh Token 삭제
refreshTokenRepository.deleteBySessionId(sessionId);
```

## 타임아웃 및 예외 처리

| 시나리오 | 예외 타입 | HTTP Status |
|---------|-----------|-------------|
| API Key 미등록 | `InvalidApiKeyException` | 401 |
| 캐릭터 존재하지 않음 | `CharacterNotFoundException` | 404 |
| Refresh Token 미등록/만료 | `InvalidRefreshTokenException` | 401 |
| 토큰 위변조 | `JwtValidationException` | 401 |

## 관련 이슈

- Issue #279: Refresh Token Rotation 구현
- CLAUDE.md Section 18: Spring Security 6.x Filter Best Practice
