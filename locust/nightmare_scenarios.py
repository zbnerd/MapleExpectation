"""
Nightmare Chaos Test - E2E Load Test Scenarios

Nightmare 테스트의 E2E 시나리오를 실행하여 시스템 취약점을 검증합니다.
각 시나리오는 특정 장애 상황을 시뮬레이션하고 시스템의 반응을 측정합니다.

사용법:
    # N08: Redis 장애 시 Thundering Herd
    locust -f nightmare_scenarios.py --tags n08 -u 100 -r 50 -t 60s --host http://localhost:8080

    # N10: ThreadPool 포화 테스트
    locust -f nightmare_scenarios.py --tags n10 -u 200 -r 100 -t 120s --host http://localhost:8080

    # N18: Deep Paging 성능 테스트
    locust -f nightmare_scenarios.py --tags n18 -u 50 -r 10 -t 60s --host http://localhost:8080

    # 전체 Nightmare 시나리오
    locust -f nightmare_scenarios.py -u 100 -r 20 -t 180s --host http://localhost:8080

환경 변수:
    NEXON_API_KEY: Nexon Open API 키 (인증이 필요한 테스트용)
    LOGIN_IGN: 로그인용 캐릭터 닉네임

메트릭 검증:
    테스트 전/후 Prometheus 메트릭 비교:
    - hikaricp_connections_active
    - hikaricp_connections_timeout_total
    - redis_commands_processed_total
    - http_server_requests_seconds_bucket
"""
from locust import HttpUser, task, between, tag, events
from urllib.parse import quote
import random
import os
import time
import logging
import threading
from datetime import datetime

# 로깅 설정
logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

# ============== 테스트 데이터 ==============

TEST_CHARACTERS = [
    "아델", "진격캐넌", "글자", "뉴비렌붕잉", "긱델",
    "고딩", "물주", "쯔단", "강은호", "팀에이컴퍼니", "흡혈", "꾸장"
]

# Hot Key 테스트용 (N05, N08)
HOT_KEY_CHARACTERS = ["강은호"]  # 동일 키에 집중

# Deep Paging 테스트용 페이지 범위
DEEP_PAGE_NUMBERS = [1, 10, 100, 500, 1000]


# ============== 응답 검증 유틸리티 ==============

class NightmareValidator:
    """Nightmare 시나리오 응답 검증"""

    @staticmethod
    def validate_response(response, request_name, expected_timeout=False):
        """
        응답 검증

        Args:
            response: HTTP 응답 객체
            request_name: 요청 이름 (로깅용)
            expected_timeout: 타임아웃이 예상되는 시나리오인지

        Returns:
            (is_valid, error_message, metrics_dict)
        """
        metrics = {
            "status_code": response.status_code,
            "response_time_ms": response.elapsed.total_seconds() * 1000,
            "request_name": request_name,
            "timestamp": datetime.now().isoformat()
        }

        # 타임아웃 시나리오 (N06, N10)
        if expected_timeout:
            if response.status_code == 503 or response.elapsed.total_seconds() > 5:
                return True, None, metrics  # 예상된 타임아웃
            return True, None, metrics

        # 일반 검증
        if response.status_code >= 500:
            return False, f"Server Error: {response.status_code}", metrics

        if response.status_code >= 400:
            return False, f"Client Error: {response.status_code}", metrics

        try:
            data = response.json()
        except Exception as e:
            return False, f"JSON Parse Error: {str(e)}", metrics

        # 응답 형식에 따른 처리
        if "success" in data:
            if not data.get("success", False):
                error_msg = data.get("error", {}).get("message", "Unknown error")
                return False, f"Business Error: {error_msg}", metrics
            result = data.get("data", {})
        else:
            result = data

        metrics["has_data"] = bool(result)
        return True, None, metrics

    @staticmethod
    def validate_pagination_response(response, page_number):
        """페이지네이션 응답 검증 (N18)"""
        metrics = {
            "page_number": page_number,
            "response_time_ms": response.elapsed.total_seconds() * 1000,
            "status_code": response.status_code
        }

        if response.status_code >= 400:
            return False, f"Error: {response.status_code}", metrics

        try:
            data = response.json()
            metrics["total_elements"] = data.get("totalElements", 0)
            metrics["number_of_elements"] = data.get("numberOfElements", 0)
        except Exception as e:
            return False, f"JSON Parse Error: {str(e)}", metrics

        return True, None, metrics


# ============== 메트릭 수집기 ==============

class MetricsCollector:
    """Prometheus 메트릭 수집"""

    def __init__(self):
        self.metrics_history = []
        self._lock = threading.Lock()

    def record(self, metrics):
        """메트릭 기록"""
        with self._lock:
            self.metrics_history.append(metrics)

    def get_summary(self):
        """메트릭 요약"""
        if not self.metrics_history:
            return {}

        response_times = [m.get("response_time_ms", 0) for m in self.metrics_history]
        return {
            "total_requests": len(self.metrics_history),
            "avg_response_time_ms": sum(response_times) / len(response_times),
            "max_response_time_ms": max(response_times),
            "min_response_time_ms": min(response_times),
        }


# 전역 메트릭 수집기
nightmare_metrics = MetricsCollector()


# ============== Nightmare 시나리오 클래스 ==============

class N08ThunderingHerdRedisDeathUser(HttpUser):
    """
    N08: Thundering Herd - Redis 장애 시 MySQL 폴백 폭주

    시나리오:
    - 100+ 동시 사용자가 동일 키(Hot Key)에 접근
    - Redis 장애 상황에서 모든 요청이 MySQL로 폴백
    - HikariCP 커넥션 풀 고갈 여부 검증

    메트릭 검증:
    - hikaricp_connections_active 급증
    - hikaricp_connections_timeout_total 증가
    - 응답 시간 급격한 증가
    """
    wait_time = between(0.1, 0.5)  # 매우 짧은 대기로 부하 집중

    def on_start(self):
        logger.info("[N08] Starting Thundering Herd Redis Death scenario")

    @tag('n08', 'thundering_herd', 'redis_death')
    @task(10)  # 높은 가중치로 집중 공격
    def hit_hot_key(self):
        """동일 Hot Key에 집중 요청"""
        user_ign = random.choice(HOT_KEY_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')

        with self.client.get(
            f"/api/v3/characters/{encoded_ign}/expectation",
            name="/n08/hot_key_attack",
            catch_response=True
        ) as response:
            is_valid, error_msg, metrics = NightmareValidator.validate_response(
                response, "/n08/hot_key_attack"
            )
            nightmare_metrics.record(metrics)

            if not is_valid:
                response.failure(error_msg)
                logger.warning(f"[N08] Request failed: {error_msg}")
            else:
                response.success()

    @tag('n08')
    @task(1)
    def distributed_request(self):
        """분산된 요청 (비교용)"""
        user_ign = random.choice(TEST_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')

        with self.client.get(
            f"/api/v3/characters/{encoded_ign}/expectation",
            name="/n08/distributed",
            catch_response=True
        ) as response:
            is_valid, error_msg, metrics = NightmareValidator.validate_response(
                response, "/n08/distributed"
            )
            nightmare_metrics.record(metrics)

            if not is_valid:
                response.failure(error_msg)
            else:
                response.success()


class N10CallerRunsPolicyUser(HttpUser):
    """
    N10: CallerRunsPolicy Betrayal - ThreadPool 포화 시 HTTP 스레드 블로킹

    시나리오:
    - 대량의 비동기 작업 요청으로 ThreadPool 포화
    - CallerRunsPolicy 발동 시 HTTP 스레드 블로킹
    - 응답 시간 급격한 증가 확인

    메트릭 검증:
    - jvm_threads_live_threads 급증
    - http_server_requests_seconds 급증
    - 일부 요청의 응답 시간이 5초+ (비동기 작업 시간)
    """
    wait_time = between(0.05, 0.2)  # 매우 빠른 요청으로 ThreadPool 포화 유도

    def on_start(self):
        logger.info("[N10] Starting CallerRunsPolicy Betrayal scenario")

    @tag('n10', 'threadpool', 'caller_runs')
    @task
    def trigger_async_operation(self):
        """비동기 작업을 트리거하는 API 호출"""
        user_ign = random.choice(TEST_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')

        # V3 API는 내부적으로 비동기 파이프라인 사용
        with self.client.get(
            f"/api/v3/characters/{encoded_ign}/expectation",
            name="/n10/async_trigger",
            catch_response=True,
            timeout=30  # 긴 타임아웃으로 블로킹 감지
        ) as response:
            is_valid, error_msg, metrics = NightmareValidator.validate_response(
                response, "/n10/async_trigger", expected_timeout=True
            )
            nightmare_metrics.record(metrics)

            # 5초 이상 걸리면 CallerRunsPolicy 의심
            if metrics["response_time_ms"] > 5000:
                logger.warning(f"[N10] Possible CallerRunsPolicy: {metrics['response_time_ms']}ms")

            if not is_valid:
                response.failure(error_msg)
            else:
                response.success()


class N11LockFallbackUser(HttpUser):
    """
    N11: Lock Fallback Avalanche - Redis 폴백 시 커넥션 풀 고갈

    시나리오:
    - 분산 락이 필요한 작업에 대량 요청
    - Redis 폴백 시 MySQL Named Lock 사용
    - 각 Named Lock이 커넥션을 점유하여 풀 고갈

    메트릭 검증:
    - hikaricp_connections_pending 급증
    - hikaricp_connections_acquire_seconds_max 증가
    """
    wait_time = between(0.2, 0.5)

    def on_start(self):
        self.api_key = os.environ.get("NEXON_API_KEY")
        self.login_ign = os.environ.get("LOGIN_IGN", "긱델")
        self.token = None

        if self.api_key:
            self._login()
        else:
            logger.warning("[N11] NEXON_API_KEY not set, skipping authenticated tests")

    def _login(self):
        """인증 토큰 획득"""
        response = self.client.post("/auth/login", json={
            "apiKey": self.api_key,
            "userIgn": self.login_ign
        }, name="/auth/login")

        if response.status_code == 200:
            data = response.json()
            if data.get("success") and data.get("data"):
                self.token = data["data"].get("accessToken")
                if self.token:
                    self.client.headers.update({
                        "Authorization": f"Bearer {self.token}"
                    })
                    logger.info(f"[N11] Login successful")

    @tag('n11', 'lock_fallback', 'connection_pool')
    @task
    def concurrent_lock_request(self):
        """동시 락 요청 (좋아요 API 사용)"""
        if not self.token:
            return

        user_ign = random.choice([c for c in TEST_CHARACTERS if c != self.login_ign])
        encoded_ign = quote(user_ign, safe='')

        with self.client.post(
            f"/api/v2/characters/{encoded_ign}/like",
            name="/n11/lock_request",
            catch_response=True
        ) as response:
            is_valid, error_msg, metrics = NightmareValidator.validate_response(
                response, "/n11/lock_request"
            )
            nightmare_metrics.record(metrics)

            # 비즈니스 예외(중복 좋아요 등)는 성공으로 처리
            if response.status_code == 409:
                response.success()
            elif not is_valid:
                response.failure(error_msg)
            else:
                response.success()


class N18DeepPagingUser(HttpUser):
    """
    N18: Deep Paging Abyss - OFFSET 페이징 성능 저하

    시나리오:
    - 다양한 페이지 깊이에서 응답 시간 측정
    - page=1 vs page=1000 성능 비교
    - OFFSET 깊이에 따른 O(n) 복잡도 검증

    메트릭 검증:
    - 페이지 깊이와 응답 시간의 선형 관계
    - page=1000에서 응답 시간 > 100ms
    """
    wait_time = between(1, 2)

    def on_start(self):
        logger.info("[N18] Starting Deep Paging Abyss scenario")

    @tag('n18', 'deep_paging', 'pagination')
    @task(1)
    def page_1(self):
        """첫 페이지 (기준선)"""
        self._fetch_page(1)

    @tag('n18')
    @task(1)
    def page_10(self):
        """10번째 페이지"""
        self._fetch_page(10)

    @tag('n18')
    @task(1)
    def page_100(self):
        """100번째 페이지"""
        self._fetch_page(100)

    @tag('n18')
    @task(1)
    def page_500(self):
        """500번째 페이지 (깊은 페이지)"""
        self._fetch_page(500)

    @tag('n18')
    @task(1)
    def page_1000(self):
        """1000번째 페이지 (매우 깊은 페이지)"""
        self._fetch_page(1000)

    def _fetch_page(self, page_number):
        """페이지 요청 및 검증"""
        # 존재하는 페이징 API 사용 (캐릭터 목록 등)
        with self.client.get(
            f"/api/v2/characters?page={page_number}&size=10",
            name=f"/n18/page_{page_number}",
            catch_response=True
        ) as response:
            is_valid, error_msg, metrics = NightmareValidator.validate_pagination_response(
                response, page_number
            )
            nightmare_metrics.record(metrics)

            # 깊은 페이지에서 100ms 이상 걸리면 경고
            if page_number >= 100 and metrics.get("response_time_ms", 0) > 100:
                logger.warning(
                    f"[N18] Deep paging slow: page={page_number}, "
                    f"time={metrics.get('response_time_ms', 0):.2f}ms"
                )

            if response.status_code == 404:
                # 페이지가 없는 경우도 성공으로 처리 (데이터 없음)
                response.success()
            elif not is_valid:
                response.failure(error_msg)
            else:
                response.success()


class GeneralNightmareUser(HttpUser):
    """
    일반 Nightmare 시나리오 - 전체 시스템 부하 테스트

    다양한 엔드포인트에 균등하게 부하를 분산하여
    전반적인 시스템 안정성을 검증합니다.
    """
    wait_time = between(0.5, 2)

    def on_start(self):
        logger.info("[Nightmare] Starting general chaos scenario")

    @tag('general', 'v3')
    @task(3)
    def v3_expectation(self):
        """V3 기대값 API"""
        user_ign = random.choice(TEST_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')

        with self.client.get(
            f"/api/v3/characters/{encoded_ign}/expectation",
            name="/general/v3_expectation",
            catch_response=True
        ) as response:
            is_valid, error_msg, metrics = NightmareValidator.validate_response(
                response, "/general/v3_expectation"
            )
            nightmare_metrics.record(metrics)

            if not is_valid:
                response.failure(error_msg)
            else:
                response.success()

    @tag('general', 'v2')
    @task(1)
    def v2_expectation(self):
        """V2 기대값 API (레거시)"""
        user_ign = random.choice(TEST_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')

        with self.client.get(
            f"/api/v2/characters/{encoded_ign}/expectation",
            name="/general/v2_expectation",
            catch_response=True
        ) as response:
            is_valid, error_msg, metrics = NightmareValidator.validate_response(
                response, "/general/v2_expectation"
            )
            nightmare_metrics.record(metrics)

            if not is_valid:
                response.failure(error_msg)
            else:
                response.success()

    @tag('general')
    @task(2)
    def health_check(self):
        """헬스체크 (기준선)"""
        with self.client.get(
            "/actuator/health",
            name="/general/health",
            catch_response=True
        ) as response:
            if response.status_code != 200:
                response.failure(f"Health check failed: {response.status_code}")
            else:
                response.success()


# ============== 테스트 이벤트 핸들러 ==============

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    """테스트 시작 시 초기화"""
    logger.info("=" * 70)
    logger.info("  NIGHTMARE CHAOS TEST STARTING")
    logger.info("=" * 70)
    logger.info(f"  Host: {environment.host}")
    logger.info(f"  Start Time: {datetime.now().isoformat()}")
    logger.info("=" * 70)


@events.quitting.add_listener
def on_quitting(environment, **kwargs):
    """테스트 종료 시 Nightmare 리포트 출력"""
    logger.info("")
    logger.info("=" * 70)
    logger.info("  NIGHTMARE CHAOS TEST RESULTS")
    logger.info("=" * 70)

    # 기본 통계
    stats = environment.stats
    logger.info(f"  Total Requests: {stats.total.num_requests}")
    logger.info(f"  Total Failures: {stats.total.num_failures}")
    logger.info(f"  Failure Rate: {stats.total.fail_ratio:.2%}")
    logger.info(f"  Median Response Time: {stats.total.median_response_time:.0f}ms")
    logger.info(f"  P95 Response Time: {stats.total.get_response_time_percentile(0.95):.0f}ms")
    logger.info(f"  P99 Response Time: {stats.total.get_response_time_percentile(0.99):.0f}ms")
    logger.info(f"  Max Response Time: {stats.total.max_response_time:.0f}ms")
    logger.info(f"  RPS: {stats.total.current_rps:.2f}")

    # Nightmare 메트릭 요약
    summary = nightmare_metrics.get_summary()
    if summary:
        logger.info("")
        logger.info("  --- Nightmare Metrics ---")
        logger.info(f"  Recorded Requests: {summary.get('total_requests', 0)}")
        logger.info(f"  Avg Response Time: {summary.get('avg_response_time_ms', 0):.2f}ms")
        logger.info(f"  Max Response Time: {summary.get('max_response_time_ms', 0):.2f}ms")
        logger.info(f"  Min Response Time: {summary.get('min_response_time_ms', 0):.2f}ms")

    # 경고 메시지
    if stats.total.fail_ratio > 0.05:  # 5% 초과 실패
        logger.warning("")
        logger.warning("  [ALERT] Failure rate exceeded 5%!")
        logger.warning("  Check the following:")
        logger.warning("    - hikaricp_connections_timeout_total")
        logger.warning("    - redis_commands_failed_total")
        logger.warning("    - jvm_threads_live_threads")

    if stats.total.get_response_time_percentile(0.99) > 5000:  # P99 > 5s
        logger.warning("")
        logger.warning("  [ALERT] P99 response time exceeded 5 seconds!")
        logger.warning("  Possible causes:")
        logger.warning("    - N10: CallerRunsPolicy blocking HTTP threads")
        logger.warning("    - N11: Connection pool exhaustion")
        logger.warning("    - N08: Redis fallback thundering herd")

    logger.info("")
    logger.info("=" * 70)
    logger.info(f"  End Time: {datetime.now().isoformat()}")
    logger.info("=" * 70)


# ============== 사용 예시 및 검증 가이드 ==============

"""
사용 예시:

1. N08 Thundering Herd (Redis 장애 시뮬레이션):
   # Redis 장애를 시뮬레이션하려면 먼저 Toxiproxy로 Redis 연결 차단
   toxiproxy-cli toxic add -n latency -t latency -a latency=10000 redis

   # Locust 실행
   locust -f nightmare_scenarios.py --tags n08 -u 100 -r 50 -t 60s --host http://localhost:8080

2. N10 CallerRunsPolicy:
   # ThreadPool 포화를 위해 많은 동시 사용자 필요
   locust -f nightmare_scenarios.py --tags n10 -u 200 -r 100 -t 120s --host http://localhost:8080

3. N18 Deep Paging:
   # 페이지 깊이별 응답 시간 측정
   locust -f nightmare_scenarios.py --tags n18 -u 50 -r 10 -t 60s --host http://localhost:8080

4. 전체 시나리오:
   locust -f nightmare_scenarios.py -u 100 -r 20 -t 180s --host http://localhost:8080

메트릭 검증 (테스트 전/후):

   # 테스트 전
   curl -s "http://localhost:9090/api/v1/query?query=hikaricp_connections_active" > before.json

   # 테스트 실행
   locust -f nightmare_scenarios.py --tags n08 -u 100 -r 50 -t 60s --headless

   # 테스트 후
   curl -s "http://localhost:9090/api/v1/query?query=hikaricp_connections_active" > after.json

   # 비교
   diff before.json after.json
"""
