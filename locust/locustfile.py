"""
MapleExpectation Load Test Suite

사용법:
    # V3 기대값 API 테스트 (기본)
    locust -f locustfile.py --tags v3 -u 50 -r 10 -t 60s --host http://localhost:8080

    # V2 좋아요 API (인증 필요)
    export NEXON_API_KEY="your_api_key"
    export LOGIN_IGN="your_character_name"
    locust -f locustfile.py --tags like_sync_test -u 50 -r 10 -t 60s --host http://localhost:8080

    # 전체 시나리오 (Web UI)
    locust -f locustfile.py --host http://localhost:8080

재현성 보장:
    - 테스트 전 캐시 상태 확인: curl http://localhost:8080/actuator/metrics/cache.hit
    - Warm-up: 각 캐릭터 1회씩 호출 후 본 테스트 진행
    - 환경 고정: JVM -Xmx512m, Redis maxmemory 256mb, MySQL 기본 설정
"""
from locust import HttpUser, task, between, constant, tag, events
from locust.runners import MasterRunner
from urllib.parse import quote
import random
import os
import time
import logging

# 로깅 설정
logger = logging.getLogger(__name__)

# #264: 환경변수 기반 wait_time 설정 (부하테스트 튜닝)
# LOCUST_WAIT_MIN=0 LOCUST_WAIT_MAX=0 → wait_time 제거 (최대 RPS)
WAIT_MIN = float(os.environ.get("LOCUST_WAIT_MIN", "0.1"))
WAIT_MAX = float(os.environ.get("LOCUST_WAIT_MAX", "0.5"))

# ============== 응답 검증 유틸리티 ==============

class ResponseValidator:
    """API 응답 검증 유틸리티"""

    @staticmethod
    def validate_expectation_response(response, request_name):
        """
        기대값 API 응답 검증

        실패 정의:
        - HTTP 5xx: 서버 오류 (심각)
        - HTTP 4xx: 클라이언트 오류 (설정 문제)
        - success=false: 비즈니스 로직 실패 (ApiResponse 래핑 시)
        - 필수 필드 누락: API 스키마 불일치
        - expectedCount <= 0: 비정상 계산 결과

        Returns:
            (is_valid, error_message)
        """
        if response.status_code >= 500:
            return False, f"Server Error: {response.status_code}"

        if response.status_code >= 400:
            return False, f"Client Error: {response.status_code}"

        try:
            data = response.json()
        except Exception as e:
            return False, f"JSON Parse Error: {str(e)}"

        # ApiResponse 래핑 형식 vs 직접 반환 형식 모두 지원
        # ApiResponse: {"success": true, "data": {...}}
        # Direct: {"userIgn": "...", "items": [...]}
        if "success" in data:
            # ApiResponse 형식
            if not data.get("success", False):
                error_msg = data.get("error", {}).get("message", "Unknown error")
                return False, f"Business Error: {error_msg}"
            result = data.get("data", {})
        else:
            # Direct 형식 (V3 API)
            result = data

        # 필수 필드 존재 검증
        required_fields = ["userIgn", "totalCost", "items"]
        missing = [f for f in required_fields if f not in result]
        if missing:
            return False, f"Missing fields: {missing}"

        # 값 범위 검증 (expectedCount >= 0, 0은 최적 잠재능력 보유 시 정상)
        items = result.get("items", [])
        for item in items:
            expected_count = item.get("expectedCount", -1)
            if expected_count < 0:
                return False, f"Invalid expectedCount: {expected_count} for {item.get('itemName')}"

        return True, None

    @staticmethod
    def validate_like_response(response, request_name):
        """좋아요 API 응답 검증"""
        if response.status_code >= 500:
            return False, f"Server Error: {response.status_code}"

        # 401/403은 인증 문제 (테스트 설정 오류)
        if response.status_code in [401, 403]:
            return False, f"Auth Error: {response.status_code} - Check NEXON_API_KEY"

        if response.status_code >= 400:
            return False, f"Client Error: {response.status_code}"

        try:
            data = response.json()
        except Exception as e:
            return False, f"JSON Parse Error: {str(e)}"

        if not data.get("success", False):
            # 중복 좋아요, 자기 좋아요 등은 비즈니스 예외 (실패로 카운트 안함)
            error_code = data.get("error", {}).get("code", "")
            if error_code in ["DUPLICATE_LIKE", "SELF_LIKE_NOT_ALLOWED"]:
                return True, None  # 예상된 비즈니스 예외
            return False, f"Business Error: {data.get('error', {}).get('message', 'Unknown')}"

        return True, None


# ============== 테스트 데이터 ==============

# 실제 DB에 존재하는 캐릭터 닉네임 (캐시 상태 확인 용이)
TEST_CHARACTERS = [
    "아델", "진격캐넌", "글자", "뉴비렌붕잉", "긱델",
    "고딩", "물주", "쯔단", "강은호", "팀에이컴퍼니", "흡혈", "꾸장"
]

# #264: V4 전용 캐릭터 풀 (3개로 축소 → L1 캐시 히트율 극대화)
V4_TEST_CHARACTERS = ["강은호", "아델", "긱델"]

# Warm-up용 (캐시 프라이밍)
WARMUP_CHARACTERS = ["강은호", "아델"]  # 가장 많이 사용되는 캐릭터


# ============== 부하 테스트 클래스 ==============

class MapleExpectationLoadTest(HttpUser):
    """
    기본 부하 테스트 (인증 없음)

    테스트 대상:
    - V3 기대값 API (비동기 파이프라인)
    - V2 기대값 API (레거시)

    검증 항목:
    - HTTP 상태 코드 (2xx 성공)
    - success=true
    - 필수 필드 존재
    - expectedCount > 0
    """
    # abstract = True 제거 - 실제 실행되도록 변경

    # #264: 환경변수 기반 wait_time (LOCUST_WAIT_MIN=0 LOCUST_WAIT_MAX=0 → 최대 RPS)
    wait_time = between(WAIT_MIN, WAIT_MAX)

    def on_start(self):
        """테스트 시작 시 Warm-up 실행 (V4 태그 시 스킵)"""
        # V4 부하테스트 시 warmup 스킵 (--tags v4)
        import sys
        if 'v4' in sys.argv:
            logger.info("[Locust] Skipping warm-up for V4 test")
            return

        logger.info("[Locust] Starting warm-up phase...")
        for char in WARMUP_CHARACTERS:
            try:
                encoded_char = quote(char, safe='')
                resp = self.client.get(
                    f"/api/v3/characters/{encoded_char}/expectation",
                    name="/warmup/v3/expectation"
                )
                logger.info(f"[Warmup] {char}: {resp.status_code}")
            except Exception as e:
                logger.warning(f"[Warmup] {char} failed: {e}")
        logger.info("[Locust] Warm-up completed")

    @tag('v3')
    @task(3)  # V3가 주요 API이므로 가중치 높임
    def test_v3_expectation(self):
        """V3 기대값 API 테스트 (비동기 파이프라인)"""
        user_ign = random.choice(TEST_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')
        with self.client.get(
            f"/api/v3/characters/{encoded_ign}/expectation",
            name="/v3/expectation",
            catch_response=True
        ) as response:
            is_valid, error_msg = ResponseValidator.validate_expectation_response(
                response, "/v3/expectation"
            )
            if not is_valid:
                response.failure(error_msg)
            else:
                response.success()

    @tag('v2')
    @task(1)
    def test_v2_expectation_legacy(self):
        """V2 기대값 API 테스트 (레거시)"""
        user_ign = random.choice(TEST_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')
        with self.client.get(
            f"/api/v2/characters/{encoded_ign}/expectation",
            name="/v2/expectation",
            catch_response=True
        ) as response:
            is_valid, error_msg = ResponseValidator.validate_expectation_response(
                response, "/v2/expectation"
            )
            if not is_valid:
                response.failure(error_msg)
            else:
                response.success()

    @tag('v4')
    @task(3)
    def test_v4_expectation(self):
        """V4 기대값 API 테스트 (Singleflight + GZIP 응답, #262)"""
        # #264: V4 전용 캐릭터 풀 (3개) → L1 캐시 히트율 극대화
        user_ign = random.choice(V4_TEST_CHARACTERS)
        encoded_ign = quote(user_ign, safe='')
        # GZIP 응답 요청 - 서버 CPU 절감 및 응답 시간 단축
        headers = {"Accept-Encoding": "gzip"}
        with self.client.get(
            f"/api/v4/characters/{encoded_ign}/expectation",
            name="/v4/expectation",
            headers=headers,
            catch_response=True
        ) as response:
            # V4 GZIP 응답 검증 (#262)
            if response.status_code >= 500:
                response.failure(f"Server Error: {response.status_code}")
            elif response.status_code >= 400:
                response.failure(f"Client Error: {response.status_code}")
            else:
                try:
                    # GZIP 응답 확인 (Content-Encoding: gzip)
                    content_encoding = response.headers.get("Content-Encoding", "")
                    is_gzip = "gzip" in content_encoding.lower()

                    # Python requests 라이브러리가 자동으로 GZIP 압축 해제함
                    data = response.json()

                    # V4 응답 형식: userIgn, totalExpectedCost
                    if "userIgn" in data and "totalExpectedCost" in data:
                        # GZIP 응답 성공 시 로그 (디버깅용)
                        if is_gzip:
                            logger.debug(f"[V4] GZIP response: {len(response.content)} bytes")
                        response.success()
                    else:
                        response.failure(f"Missing required fields in response: {list(data.keys())[:5]}")
                except Exception as e:
                    response.failure(f"JSON Parse Error: {str(e)}")


class LikeSyncLoadTest(HttpUser):
    """
    Issue #147 DoD 검증용 부하 테스트

    목표: 1,000 TPS 부하 중 동기화 작업 시 유실률 0%

    환경 변수:
        NEXON_API_KEY: Nexon Open API 키
        LOGIN_IGN: 로그인용 캐릭터 닉네임

    사용법:
        export NEXON_API_KEY="your_api_key"
        export LOGIN_IGN="your_character_name"
        locust -f locustfile.py --tags like_sync_test -u 50 -r 10 -t 60s
    """
    abstract = True  # --tags v3 필터링 시 인스턴스화 방지
    wait_time = between(0.1, 0.5)  # 고부하를 위해 짧은 대기 시간

    target_characters = [c for c in TEST_CHARACTERS if c != os.environ.get("LOGIN_IGN", "긱델")]

    def on_start(self):
        """테스트 시작 시 로그인하여 JWT 토큰 획득"""
        api_key = os.environ.get("NEXON_API_KEY")
        login_ign = os.environ.get("LOGIN_IGN", "긱델")

        if not api_key:
            logger.error("[Locust] ERROR: NEXON_API_KEY environment variable not set!")
            self.token = None
            return

        response = self.client.post("/auth/login", json={
            "apiKey": api_key,
            "userIgn": login_ign
        }, name="/auth/login")

        if response.status_code == 200:
            data = response.json()
            if data.get("success") and data.get("data"):
                self.token = data["data"].get("accessToken")
                if self.token:
                    self.client.headers.update({
                        "Authorization": f"Bearer {self.token}"
                    })
                    logger.info(f"[Locust] Login successful for {login_ign}")
                else:
                    logger.error(f"[Locust] Login response missing accessToken: {data}")
            else:
                logger.error(f"[Locust] Login failed: {data}")
        else:
            logger.error(f"[Locust] Login HTTP error: {response.status_code}")
            self.token = None

    @tag('like_sync_test')
    @task
    def test_like_with_auth(self):
        """인증된 좋아요 요청 (Issue #147 DoD 검증)"""
        if not hasattr(self, 'token') or not self.token:
            return

        user_ign = random.choice(self.target_characters)
        encoded_ign = quote(user_ign, safe='')
        with self.client.post(
            f"/api/v2/characters/{encoded_ign}/like",
            name="/v2/like (authenticated)",
            catch_response=True
        ) as response:
            is_valid, error_msg = ResponseValidator.validate_like_response(
                response, "/v2/like"
            )
            if not is_valid:
                response.failure(error_msg)
            else:
                response.success()


# ============== 테스트 리포트 이벤트 ==============

@events.quitting.add_listener
def on_quitting(environment, **kwargs):
    """테스트 종료 시 요약 리포트 출력"""
    if environment.stats.total.fail_ratio > 0.01:  # 1% 초과 실패
        logger.warning(f"[ALERT] Failure rate exceeded 1%: {environment.stats.total.fail_ratio:.2%}")

    logger.info("=" * 60)
    logger.info("Test Summary")
    logger.info("=" * 60)
    logger.info(f"Total Requests: {environment.stats.total.num_requests}")
    logger.info(f"Failures: {environment.stats.total.num_failures}")
    logger.info(f"Failure Rate: {environment.stats.total.fail_ratio:.2%}")
    logger.info(f"Median Response Time: {environment.stats.total.median_response_time}ms")
    logger.info(f"P95 Response Time: {environment.stats.total.get_response_time_percentile(0.95)}ms")
    logger.info(f"P99 Response Time: {environment.stats.total.get_response_time_percentile(0.99)}ms")
    logger.info(f"RPS: {environment.stats.total.current_rps}")
    logger.info("=" * 60)
