# Character Lookup Sequence

## 개요

사용자가 캐릭터 이름(IGN)으로 캐릭터 정보를 조회하는 비동기 시퀀스입니다.

## 비즈니스 흐름

1. **Phase 2 (Light Snapshot)**: Redis/DB에서 캐릭터 조회
2. **Phase 3 (Async Worker)**: 데이터가 없으면 큐에 작업 등록 및 이벤트 대기
3. **Phase 4 (Full Snapshot)**: 기본 정보 보강 (worldName이 null이면 API 호출)

## 시퀀스 다이어그램

```mermaid
sequenceDiagram
    autonumber

    actor User as 사용자
    participant AuthCtrl as AuthController
    participant CharFacade as GameCharacterFacade
    participant CharService as GameCharacterService
    participant Cache as TieredCache
    participant Queue as CharacterJobQueue
    participant Topic as CharacterEventTopic
    participant Worker as CharacterWorker
    participant NexonAPI as NexonOpenAPI
    participant DB as MySQL

    Note over User,DB: Phase 2: Light Snapshot (캐시 조회)

    User->>CharFacade: findCharacterByUserIgn(userIgn)
    activate CharFacade

    CharFacade->>CharService: isNonExistent(userIgn)
    CharService->>Cache: get(CHARACTER_NON_EXISTENT)
    Cache-->>CharService: null (미등록)

    CharFacade->>CharService: getCharacterIfExist(userIgn)
    CharService->>Cache: get(CHARACTER_DATA)
    Cache-->>CharService: null (미등록)

    Note over CharFacade,Worker: Phase 3: Async Worker (데이터 생성)

    CharFacade->>CharFacade: waitForWorkerResult(userIgn)
    activate CharFacade

    par 이벤트 리스너 등록
        CharFacade->>Topic:addListener(callback)
        Topic-->>CharFacade: listenerId
    and 작업 큐 등록
        CharFacade->>Queue: offer(userIgn)
        Note right of Queue: 작업 등록 로그
    end

    Note over Worker,NexonAPI: Worker가 큐에서 작업 처리

    Worker->>Queue: take()
    Queue-->>Worker: userIgn

    Worker->>NexonAPI: getCharacterBasic(userIgn)
    NexonAPI-->>Worker: CharacterBasicDTO

    Worker->>NexonAPI: getCharacterPop(userIgn)
    NexonAPI-->>Worker: CharacterPopDTO

    Worker->>DB: save(character)
    DB-->>Worker: saved

    Worker->>Topic: publish("DONE")

    Note over CharFacade,Topic: 이벤트 수신 및 완료

    Topic->>CharFacade: callback(channel, "DONE")
    activate CharFacade
    CharFacade->>CharService: getCharacterIfExist(userIgn)
    CharService->>Cache: get(CHARACTER_DATA)
    Cache-->>CharService: character
    CharService-->>CharFacade: Optional.of(character)
    CharFacade->>CharFacade: future.complete(character)
    deactivate CharFacade

    CharFacade-->>CharFacade: character (awaitFuture)
    deactivate CharFacade

    Note over CharFacade,NexonAPI: Phase 4: Full Snapshot (기본 정보 보강)

    CharFacade->>CharService: enrichCharacterBasicInfo(character)
    activate CharService

    alt worldName == null
        CharService->>NexonAPI: getCharacterBasic(userIgn)
        NexonAPI-->>CharService: dto
        CharService->>DB: updateBasicInfo(dto)
        CharService->>Cache: put(CHARACTER_DATA)
    else worldName != null
        Note right of CharService: 이미 보강됨, 스킵
    end

    CharService-->>CharFacade: enrichedCharacter
    deactivate CharService

    CharFacade-->>User: GameCharacter
    deactivate CharFacade

    Note over User,DB: 정상 종료 (캐릭터 정보 반환)
```

## 관련 컴포넌트

| 컴포넌트 | 경로 | 역할 |
|---------|------|------|
| GameCharacterFacade | `service/v2/facade/GameCharacterFacade.java` | 캐릭터 조회 Facade |
| GameCharacterService | `service/v2/GameCharacterService.java` | 비즈니스 로직 |
| CharacterWorker | `service/v2/worker/CharacterWorker.java` | 비동기 작업 처리 |
| CharacterJobQueue | `core/port/out/MessageQueue.java` | 작업 큐 |
| CharacterEventTopic | `core/port/out/MessageTopic.java` | 이벤트 버스 |

## 핵심 로직

### 1. 캐시 조회 패스
```java
// Cache Hit → 즉시 반환
gameCharacterService.getCharacterIfExist(userIgn)
    .ifPresent(character -> return character);
```

### 2. 비동기 작업 대기 패스
```java
// Cache Miss → 큐 등록 + 이벤트 대기
performQueueOffer(userIgn);
awaitFuture(future, userIgn, context);
```

### 3. 이벤트 기반 완료 통지
```java
characterEventTopic.addListener(String.class, (channel, msg) -> {
    if ("DONE".equals(msg)) {
        future.complete(character);
    }
});
```

## 타임아웃 및 예외 처리

| 시나리오 | 예외 타입 | HTTP Status |
|---------|-----------|-------------|
| 캐릭터 존재하지 않음 | `CharacterNotFoundException` | 404 |
| 작업 대기 타임아웃 (10초) | `CompletionException(TimeoutException)` | 503 + Retry-After |
| 외부 API 실패 | `InternalSystemException` | 500 |

## 관련 이슈

- Issue #169: TimeoutException 전파 수정
- Issue #207: 경량 테스트 강제 규칙
