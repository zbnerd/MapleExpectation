# ADR-001: Jackson Streaming API 도입

## 상태
Accepted

## 맥락 (Context)

Nexon Open API의 장비 데이터 응답은 평균 200~300KB에 달합니다.
기존 `ObjectMapper.readValue()` DOM 방식으로 처리 시 다음 문제가 발생했습니다:

- 동시 요청 50건 초과 시 OOM 발생
- Full GC 빈번 발생
- 메모리 사용량 급증

## 검토한 대안 (Options Considered)

### 옵션 A: Heap 메모리 증설
```yaml
JAVA_OPTS: -Xmx2g
```
- 장점: 코드 변경 없음
- 단점: 인프라 비용 증가, GC 시간 증가, 근본 해결 아님
- **결론: 기각**

### 옵션 B: 외부 API 페이징 요청
- 장점: 단일 응답 크기 감소
- 단점: 외부 API가 페이징 미지원
- **결론: 기각 (API 제약)**

### 옵션 C: Jackson Streaming API
```java
JsonParser parser = factory.createParser(inputStream);
while (parser.nextToken() != null) {
    // 토큰 단위 처리
}
```
- 장점: 메모리 사용량 90% 감소
- 단점: 구현 복잡도 증가
- **결론: 채택**

## 결정 (Decision)

**Jackson Streaming API를 사용하여 토큰 단위로 JSON을 파싱합니다.**

구현은 `EquipmentStreamingParser` 클래스로 캡슐화하여 비즈니스 로직에서는 기존과 동일한 인터페이스를 사용합니다.

**핵심 구현:**
```java
// maple.expectation.parser.EquipmentStreamingParser
@Component
public class EquipmentStreamingParser {
    private final JsonFactory factory = new JsonFactory();
    private final LogicExecutor executor;

    public List<CubeCalculationInput> parseCubeInputs(byte[] rawJsonData) {
        return executor.executeWithTranslation(
                () -> this.executeParsingProcessForField(rawJsonData, "item_equipment", context),
                ExceptionTranslator.forMaple(),
                context
        );
    }
}
```

**특징:**
- `LogicExecutor.executeWithFinally()`로 리소스 해제 보장
- GZIP 압축 데이터 자동 감지 및 처리
- 15개 필드 매핑 (장비 정보, 잠재능력, 에디셔널, 스타포스)

## 결과 (Consequences)

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| Peak Heap | ~600MB | ~60MB | **-90%** |
| GC 횟수 | 빈번 | 최소 | **-93%** |
| OOM 발생 | 50 동시 | 미발생 | **해결** |

## 참고 자료
- Jackson Streaming API 공식 문서
- `maple.expectation.parser.EquipmentStreamingParser`
- Issue #240: V4 프리셋 지원 확장
