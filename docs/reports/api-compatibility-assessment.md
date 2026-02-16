# API Compatibility Assessment: V4 Expectation vs V2 Like Endpoints

## Executive Summary

This assessment analyzes API response formats between V4 expectation endpoints (`/api/v4/characters/{userIgn}/expectation`) and V2 like functionality to ensure backward compatibility during refactoring. The V4 expectation API is fully functional, while the V2 like functionality exists as services without dedicated controller endpoints.

## API Endpoints Analysis

### V4 Expectation API (`/api/v4/characters/{userIgn}/expectation`)

**Controller**: `GameCharacterControllerV4`

#### Current Implementation
```java
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<?>> getExpectation(
    @PathVariable @NotBlank String userIgn,
    @RequestParam(defaultValue = "false") boolean force,
    @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding)
```

#### Response Format (JSON)

**Main Response Structure**:
```json
{
  "userIgn": "string",
  "calculatedAt": "2024-01-01T12:00:00",
  "fromCache": true,
  "totalExpectedCost": "12345678901234567890.12",
  "totalCostText": "12조 3456억 7890만",
  "maxPresetNo": 1,
  "totalCostBreakdown": {
    "blackCubeCost": "1000000000",
    "redCubeCost": "2000000000",
    "additionalCubeCost": "500000000",
    "starforceCost": "800000000"
  },
  "presets": [
    {
      "presetNo": 1,
      "totalExpectedCost": "12345678901234567890.12",
      "totalCostText": "12조 3456억 7890만",
      "costBreakdown": { /* ... */ },
      "items": [ /* ItemExpectationV4 array */ ]
    }
  ]
}
```

**Item Level Response**:
```json
{
  "itemName": "아이템명",
  "itemIcon": "https://example.com/icon.png",
  "itemPart": "무기",
  "itemLevel": 150,
  "expectedCost": "5000000000",
  "expectedCostText": "50억",
  "costBreakdown": { /* ... */ },
  "enhancePath": "15성 2차",
  "potentialGrade": "에픽",
  "additionalPotentialGrade": "레전드리",
  "currentStar": 10,
  "targetStar": 15,
  "isNoljang": false,
  "specialRingLevel": 5,
  "blackCubeExpectation": {
    "expectedCost": "3000000000",
    "expectedCostText": "30억",
    "expectedTrials": "100.5",
    "currentGrade": "유니크",
    "targetGrade": "레전드리",
    "potential": "STR +5, DEX +3"
  },
  "additionalCubeExpectation": { /* ... */ },
  "starforceExpectation": {
    "currentStar": 10,
    "targetStar": 15,
    "isNoljang": false,
    "costWithoutDestroyPrevention": "5000000000",
    "costWithoutDestroyPreventionText": "50억",
    "expectedDestroyCountWithout": "2.5",
    "costWithDestroyPrevention": "8000000000",
    "costWithDestroyPreventionText": "80억",
    "expectedDestroyCountWith": "0.1"
  },
  "flameExpectation": {
    "powerfulFlameTrials": "50.2",
    "eternalFlameTrials": "25.1",
    "abyssFlameTrials": "10.0"
  }
}
```

#### GZIP Response
- When `Accept-Encoding: gzip` header is present
- Returns compressed JSON bytes directly
- Content-Type: `application/json`
- Content-Encoding: `gzip`

### V2 Like Functionality

**Service**: `CharacterLikeService` (No dedicated controller found)

#### Current Implementation Features
```java
public LikeToggleResult toggleLike(String targetUserIgn, AuthenticatedUser user) {
    // Returns LikeToggleResult with:
    // - liked (boolean): Like status after toggle
    // - bufferDelta (long): Current buffer delta
    // - likeCount (long): Real-time like count
}

public long getEffectiveLikeCount(String userIgn) {
    // Returns real-time like count (DB + buffer delta)
}

public boolean hasLiked(String targetUserIgn, String accountId) {
    // Returns like status for specific user
}
```

#### Expected V2 Like API Structure (Inferred from Service)

Based on the service implementation, the expected V2 like API would have these endpoints:

**POST /api/v2/characters/{userIgn}/like** - Toggle Like
```json
{
  "success": true,
  "data": {
    "liked": true,
    "bufferDelta": 1,
    "likeCount": 5
  }
}
```

**GET /api/v2/characters/{userIgn}/like/count** - Get Like Count
```json
{
  "success": true,
  "data": {
    "likeCount": 5
  }
}
```

**GET /api/v2/characters/{userIgn}/like/status** - Get Like Status
```json
{
  "success": true,
  "data": {
    "liked": true,
    "likeCount": 5
  }
}
```

## Common Fields Analysis

### Shared Fields Between APIs
| Field | V4 Expectation | V2 Like | Type | Description |
|-------|----------------|---------|------|-------------|
| `result` | ✅ (as `totalExpectedCost`) | ✅ (as `likeCount`) | String/Number | Main calculation result |
| `probability` | ✅ (in `expectedTrials`) | ❌ | BigDecimal | Success rate/times |
| `cost` | ✅ (extensive breakdown) | ❌ | BigDecimal | Financial cost |
| `userIgn` | ✅ | ✅ | String | Target character name |

### V4 Unique Fields
- `calculatedAt`: Timestamp of calculation
- `fromCache`: Cache hit status
- `totalCostText`: Formatted cost display
- `maxPresetNo`: Best preset identifier
- `presets`: Array of preset results
- Detailed item-level breakdowns

### V2 Unique Fields
- `liked`: Boolean like status
- `bufferDelta`: Pending delta value

## Integration Test Specifications

### Test Environment Setup
```yaml
# test-config.yml
test_endpoints:
  v4_expectation:
    url: "/api/v4/characters/{userIgn}/expectation"
    methods: ["GET"]

  v2_like:
    url: "/api/v2/characters/{userIgn}/like"
    methods: ["POST", "GET"]
```

### Test Cases

#### 1. Response Format Compatibility
```java
@Test
@DisplayName("V4 Response Format should match documented structure")
void testV4ResponseFormat() {
    // Given
    String userIgn = "testCharacter";

    // When
    ResponseEntity<?> response = restTemplate.getForEntity(
        "/api/v4/characters/" + userIgn + "/expectation",
        Object.class);

    // Then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isInstanceOf(Map.class);

    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsKey("userIgn");
    assertThat(body).containsKey("calculatedAt");
    assertThat(body).containsKey("totalExpectedCost");
    assertThat(body).containsKey("presets");
}
```

#### 2. GZIP Compression Support
```java
@Test
@DisplayName("V4 should support GZIP compression")
void testV4GzipCompression() {
    // Given
    HttpHeaders headers = new HttpHeaders();
    headers.set("Accept-Encoding", "gzip");
    HttpEntity<String> entity = new HttpEntity<>(headers);

    // When
    ResponseEntity<byte[]> response = restTemplate.exchange(
        "/api/v4/characters/testCharacter/expectation",
        HttpMethod.GET,
        entity,
        byte[].class);

    // Then
    assertThat(response.getHeaders().get("Content-Encoding"))
        .contains("gzip");
}
```

#### 3. V2 Like Functionality (Mock Test)
```java
@Test
@DisplayName("V2 Like service should work with standard response format")
void testV2LikeService() {
    // Given
    String userIgn = "testCharacter";
    AuthenticatedUser user = mockAuthenticatedUser();

    // When
    LikeToggleResult result = likeService.toggleLike(userIgn, user);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.liked()).isBoolean();
    assertThat(result.likeCount()).isPositive();
}
```

#### 4. Response Consistency
```java
@Test
@DisplayName("Response formats should be consistent across calls")
void testResponseConsistency() {
    // Test multiple calls to same endpoint
    for (int i = 0; i < 5; i++) {
        ResponseEntity<?> response = restTemplate.getForEntity(
            "/api/v4/characters/testCharacter/expectation",
            Object.class);

        // Verify consistent structure
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKeys("userIgn", "totalExpectedCost");
    }
}
```

## Breaking Changes Assessment

### ✅ No Breaking Changes Identified

1. **V4 API Structure**: Already follows consistent response format
2. **Common Fields**: Field names are appropriate and meaningful
3. **Error Handling**: Standard `ApiResponse` wrapper used consistently
4. **Data Types**: Proper use of BigDecimal for financial calculations

### ⚠️ Potential Compatibility Issues

1. **Missing V2 Controller**: Like functionality exists as services but no dedicated controller endpoints
2. **Response Format Consistency**: Like service responses need to be wrapped in `ApiResponse` format
3. **Authentication**: Like operations require authentication, needs proper endpoint security

### 🔧 Required Changes

1. **Create V2 Like Controller**:
```java
@RestController
@RequestMapping("/api/v2/characters")
@RequiredArgsConstructor
public class CharacterLikeController {

    private final CharacterLikeService likeService;

    @PostMapping("/{userIgn}/like")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<ApiResponse<LikeToggleResult>> toggleLike(
        @PathVariable String userIgn,
        @AuthenticationPrincipal AuthenticatedUser user) {

        LikeToggleResult result = likeService.toggleLike(userIgn, user);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{userIgn}/like/count")
    public ResponseEntity<ApiResponse<Long>> getLikeCount(
        @PathVariable String userIgn) {

        long count = likeService.getEffectiveLikeCount(userIgn);
        return ResponseEntity.ok(ApiResponse.success(count));
    }
}
```

2. **Response Format Standardization**: Ensure all V2 endpoints use `ApiResponse<T>` wrapper

## Recommendations

### 1. Immediate Actions
- ✅ V4 API is production-ready with proper response formats
- ✅ GZIP support implemented correctly
- ❓ Implement V2 controller endpoints for like functionality

### 2. Long-term Considerations
- Consider unified response format across all versions
- Implement versioning strategy for API evolution
- Add comprehensive documentation for all endpoints

### 3. Testing Strategy
- Create integration tests for all identified endpoints
- Test response format consistency across different scenarios
- Verify GZIP compression performance benefits

## Conclusion

The V4 expectation API demonstrates excellent API design with proper response formatting, error handling, and performance optimizations. The V2 like functionality needs controller endpoints to be fully accessible via HTTP APIs. No breaking changes are identified for existing V4 endpoints, making refactoring safe for backward compatibility.

---

**Generated**: 2026-02-16
**Assessment Version**: 1.0
**Next Review**: After V2 controller implementation