# Resilience Guide

> **상위 문서:** [CLAUDE.md](../CLAUDE.md)
>
> **Last Updated:** 2026-02-05
> **Applicable Versions:** Resilience4j 2.2.0, Spring Boot 3.5.4
> **Documentation Version:** 1.0

이 문서는 MapleExpectation 프로젝트의 회복 탄력성(Resilience) 패턴 및 외부 API 장애 대응 전략을 정의합니다.

## Terminology

| 용어 | 정의 |
|------|------|
| **Circuit Breaker** | 장애 확산 방지를 위한 회로 차단 패턴 |
| **Graceful Degradation** | 장애 시 서비스 가용성 유지 전략 |
| **Fallback** | 장애 시 대체 동작 제공 |
| **Marker Interface** | 서킷브레이커 실패 기록 여부 결정 |

---

# 🛡️ 외부 API 장애 대응 전략 (Resilience Strategy)

## 1. 개요
넥슨 오픈 API(외부 의존성)의 장애 또는 네트워크 지연 상황에서도 시스템 전체의 마비를 방지하고, 사용자에게 중단 없는 서비스를 제공하기 위한 **회복 탄력성(Resilience)** 설계 명세입니다.

## 2. 장애 대응 표준 시나리오 (A/B/C)

| 시나리오 | 상황 | 대응 방식 | 사용자 영향 |
| :--- | :--- | :--- | :--- |
| **Scenario A** | API 실패 및 DB 내 캐시 존재 | 만료된 로컬 캐시 데이터를 즉시 반환 (**Degrade**) | 15분 전 데이터 노출 (서비스 유지) |
| **Scenario B** | API 실패 및 DB 내 캐시 없음 | 즉시 에러 응답 및 디스코드 알림 발송 (**Fail-fast**) | 서비스 이용 불가 안내 (빠른 피드백) |
| **Scenario C** | API 응답 지연 (3초 초과) | 타임아웃으로 호출 강제 차단 및 A/B로 분기 (**Isolation**) | 3초 후 결과 확인 (스레드 고갈 방지) |

## 3. 시스템 흐름도 (Flowchart)

```mermaid
graph TD
    Start[사용자 요청] --> Call{넥슨 API 호출}
    
    %% 정상 경로
    Call -- "성공 (3초 이내)" --> Success[결과 반환 및 캐시 갱신]
    
    %% 장애 경로 (Scenario C: 타임아웃 포함)
    Call -- "실패 / 지연(3s+)" --> Fallback{Fallback 로직 작동}
    
    %% 시나리오 분기
    Fallback -- "DB 캐시 존재 [Scenario A]" --> ReturnCache[만료된 데이터 반환]
    Fallback -- "DB 캐시 부재 [Scenario B]" --> ErrorAlert[ExternalServiceEx 발생 및 디스코드 알림]
    
    ReturnCache --> End[서비스 유지]
    ErrorAlert --> End

---

## Evidence Links
- **ResilientNexonApiClient:** `src/main/java/maple/expectation/external/impl/ResilientNexonApiClient.java`
- **Marker Interfaces:** `src/main/java/maple/expectation/global/error/exception/marker/`
- **Configuration:** `src/main/resources/application.yml` (resilience4j 섹션)
- **Tests:** `src/test/java/maple/expectation/external/ResilientNexonApiClientTest.java`

## Fail If Wrong

이 가이드가 부정확한 경우:
- **CircuitBreaker가 예상대로 동작하지 않음**: resilience4j 설정과 Marker Interface 확인
- **Fallback이 호출되지 않음**: @Retry, @CircuitBreaker 어노테이션 순서 확인
- **외부 API 장애 시 서비스 전체 마비**: Graceful Degradation 미작동 확인

### Verification Commands
```bash
# CircuitBreaker 설정 확인
grep -A 30 "resilience4j:" src/main/resources/application.yml

# Marker Interface 확인
find src/main/java -name "*Marker.java"

# ResilientNexonApiClient 구현 확인
grep -A 20 "class ResilientNexonApiClient" src/main/java/maple/expectation/external/impl/
```