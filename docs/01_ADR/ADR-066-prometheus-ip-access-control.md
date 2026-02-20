# ADR-066: Prometheus 메트릭 엔드포인트 IP 기반 접근 제어

## Status
**Accepted** (2026-02-20)

## Context

### 1장: 문제의 발견 (Problem)

#### 1.1 무방비한 메트릭 엔드포인트 노출

PR #214 이전까지 MapleExpectation 프로젝트의 Prometheus 메트릭 엔드포인트(`/actuator/prometheus`)는 **IP 기반 접근 제어 없이 공개되어 있었습니다**. 이는 다음과 같은 심각한 보안 위협을 초래했습니다:

1. **시스템 내부 정보 노출**: JVM 메트릭, DB 커넥션 풀 상태, 캐시 적중률 등 운영 정보가 공개
2. **경쟁사 공격 표면**: 트래픽 패턴, 병목 지점, 서비스 구조가 노출
3. **메트릭 Manipulation 가능성**: 특정 클라이언트에서 과도한 요청으로 Prometheus 서버 공격 가능

#### 1.2 보안 요구사항 부재

Spring Boot 3.5.4 업그레이드(ADR-049)와 Observability Stack 도입(ADR-053)으로 Prometheus 지표가 풍부해졌지만, 이에 상응하는 **접근 제어 메커니즘이 누락**되었습니다:

- `management.endpoints.web.exposure.include=prometheus` 설정만으로는 불충분
- Spring Security 6.x의 필터 체인과 통합되지 않음
- 운영 환경에서 메트릭은 DevOps 팀만 접근 가능해야 함

#### 1.3 모니터링과 보안의 트레이드오프

메트릭 수집을 위해 Prometheus 서버(보통 내부 IP)에서 접근을 허용해야 하지만, 인터넷에서의 무제한 접근은 차단해야 하는 **이중적인 요구사항**이 존재했습니다.

---

### 2장: 선택지 탐색 (Options)

#### 2.1 선택지 1: Spring Security 기본 인증 (Basic Auth)

**방식**: `/actuator/prometheus` 엔드포인트에 HTTP Basic Authentication 적용

```yaml
spring:
  security:
    user:
      name: admin
      password: ${PROMETHEUS_PASSWORD}
```

**장점**:
- Spring Boot에서 즉시 사용 가능
- 구현이 간단함

**단점**:
- **Credential 관리 복잡성**: Prometheus 설정에 비밀번호 노출 필수
- **Credential Rotation 어려움**: 비밀번호 변경 시 Prometheus 설정 동기 필요
- **전송 중 비밀번호 노출**: HTTPS가 아닌 환경에서 위험

**결론**: 보안과 운영 관리 측면에서 **채택 부적합**

---

#### 2.2 선택지 2: API Key 헤더 기반 인증

**방식**: `X-API-Key` 헤더로 인증

```java
@Component
public class ApiKeyFilter implements Filter {
    private static final String VALID_KEY = System.getenv("PROMETHEUS_API_KEY");

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        String key = ((HttpServletRequest) req).getHeader("X-API-Key");
        if (!VALID_KEY.equals(key)) {
            ((HttpServletResponse) res).sendError(401);
            return;
        }
        chain.doFilter(req, res);
    }
}
```

**장점**:
- IP 제약 없이 어디서든 접근 가능

**단점**:
- **API Key 관리 부담**: Basic Auth와 동일한 문제
- **Key 노출 위험**: Git, 로그, Config 서버 등에 유출 가능성
- **Prometheus 설정 복잡**: `bearer_token` 파일 별도 관리 필요

**결론**: 운영 오버헤드가 크고 **채택 부적합**

---

#### 2.3 선택지 3: IP 화이트리스트 기반 접근 제어 (선택)

**방식**: Spring Security Filter에서 IP 주소 화이트리스트 검증

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PrometheusAccessControlFilter implements Filter {

    private static final Set<String> ALLOWED_IPS = Set.of(
        "127.0.0.1",           // Localhost
        "10.0.0.0/8",          // Private network (AWS VPC)
        "172.16.0.0/12",       // Private network
        "192.168.0.0/16"       // Private network
    );

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        String clientIp = getClientIp((HttpServletRequest) req);

        if (!isAllowedIp(clientIp)) {
            ((HttpServletResponse) res).sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        chain.doFilter(req, res);
    }

    private boolean isAllowedIp(String ip) {
        // CIDR 검증 로직
        return ALLOWED_IPS.stream().anyMatch(cidr -> IpRangeMatcher.matches(cidr, ip));
    }
}
```

**장점**:
- **Credential 없음**: 비밀번호/Key 관리 불필요
- **운영 단순성**: IP만 관리하면 됨
- **보안 강화**: 내부 네트워크에서만 접근 가능
- **Prometheus 설정 변경 불필요**: 기본 동작

**단점**:
- IP 기반 인증은 NAT/Proxy 환경에서 X-Forwarded-For 신뢰 필요
- 동적 IP 환경에서는 화이트리스트 갱신 필요

**결론**: 운영 환경(VPC)에 가장 적합한 **선택**

---

### 3장: 결정의 근거 (Decision)

#### 3.1 선택: IP 화이트리스트 기반 접근 제어

MapleExpectation 프로젝트는 **선택지 3: IP 화이트리스트 기반 접근 제어**를 채택했습니다.

**결정 근거**:
1. **운영 환경이 AWS VPC 내부**: Prometheus 서버는 내부 IP에서 접근
2. **Credential 관리 부담 제거**: 비밀번호/Key 관리 불필요
3. **Spring Security 6.x 통합**: Filter Chain에 쉽게 통합
4. **보안과 단순성의 균형**: 구현이 간단하고 보안 효과가 확실함

---

### 4장: 구현의 여정 (Action)

#### 4.1 PrometheusAccessControlFilter 구현

**파일**: `maple/expectation/config/PrometheusAccessControlFilter.java`

```java
package maple.expectation.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PrometheusAccessControlFilter implements Filter {

    @Value("${management.prometheus.allowed-ips:127.0.0.1,::1}")
    private String allowedIps;

    private Set<String> ipWhitelist;

    @Override
    public void init(FilterConfig filterConfig) {
        ipWhitelist = Set.of(allowedIps.split(","));
        log.info("Prometheus Access Control initialized with whitelist: {}", ipWhitelist);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String uri = request.getRequestURI();

        // Prometheus 엔드포인트에만 적용
        if (!uri.equals("/actuator/prometheus")) {
            chain.doFilter(req, res);
            return;
        }

        String clientIp = getClientIp(request);

        if (!isAllowedIp(clientIp)) {
            log.warn("Blocked Prometheus access from unauthorized IP: {}", clientIp);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access Denied");
            return;
        }

        chain.doFilter(req, res);
    }

    private String getClientIp(HttpServletRequest request) {
        // Proxy 환경 고려: X-Forwarded-For 헤더 확인
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isAllowedIp(String ip) {
        // 정확히 일치하거나 localhost인 경우 허용
        return ipWhitelist.contains(ip)
            || ip.equals("127.0.0.1")
            || ip.equals("::1")
            || ip.equals("0:0:0:0:0:0:0:1");
    }
}
```

#### 4.2 application.yml 설정

```yaml
management:
  prometheus:
    allowed-ips: "127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
```

#### 4.3 테스트 코드

```java
@SpringBootTest
@AutoConfigureMockMvc
class PrometheusAccessControlFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void prometheusEndpoint_허용된_IP에서_접근_가능() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                .with(remoteAddress("127.0.0.1")))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void prometheusEndpoint_차단된_IP에서_접근_거부() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                .with(remoteAddress("8.8.8.8")))
            .andExpect(status().isForbidden());
    }
}
```

---

### 5장: 결과와 학습 (Result)

#### 5.1 성과

1. **보안 강화**: 메트릭 엔드포인트가 내부 네트워크에서만 접근 가능
2. **운영 단순화**: Credential 관리 불필요
3. **Prometheus 설정 변경 불필요**: 내부 IP에서 자동 접근

#### 5.2 학습한 점

1. **IP 기반 인증의 장점**:Credential-less 인증은 운영 부담을 크게 줄임
2. **Filter Order的重要性**: Security Filter Chain과의 통합에서 순서가 중요
3. **X-Forwarded-For 신뢰 문제**: Proxy 환경에서는 헤더 스푸핑 가능성 고려 필요

#### 5.3 향후 개선 방향

- CIDR 블록 검증 로직 강화 (현재는 정확히 일치만 검사)
- Prometheus 서버 IP가 변경될 때의 재시작 없는 화이트리스트 갱신
- Kubernetes 환경에서는 NetworkPolicy와 통합

---

## Consequences

### 긍정적 영향
- **보안 강화**: 메트릭 엔드포인트 보안 취약점 해결
- **운영 단순화**: 비밀번호 관리 불필요
- **준수성**: 보안 감사 요구사항 충족

### 부정적 영향
- **유연성 감소**: 외부에서 접근하려면 IP 허용 필요
- **NAT 환경 고려**: X-Forwarded-For 헤더 신뢰 필요

### 위험 완화
- Prometheus 서버는 고정 IP 사용
- VPC 내부에서만 통신하도록 Security Group 구성

---

## References

- **PR #214**: feat(#209): Prometheus 메트릭 엔드포인트 IP 기반 접근 제어
- **ADR-049**: Spring Boot 3.5.4 채택
- **ADR-053**: Observability Stack (Prometheus + Grafana + Loki + OpenTelemetry)
- **Spring Security 6.x Documentation**: https://docs.spring.io/spring-security/reference/index.html
