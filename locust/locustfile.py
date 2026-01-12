from locust import HttpUser, task, between, tag
import random
import os

class MapleExpectationLoadTest(HttpUser):
    """기본 부하 테스트 (인증 없음)"""
    abstract = True  # 태그 필터링 시 에러 방지

    # 각 작업 사이의 대기 시간 (1~3초 랜덤)
    wait_time = between(1, 3)

    # 테스트 데이터 (실제 DB에 있는 닉네임으로 설정하면 좋습니다)
    user_names = ["아델", "진격캐넌", "글자", "뉴비렌붕잉", "긱델", "고딩", "물주", "쯔단", "강은호", "팀에이컴퍼니", "흡혈", "꾸장"]

    @tag('v3') # V3 태그 설정
    @task
    def test_v3_expectation(self):
        user_ign = random.choice(self.user_names)
        self.client.get(f"/api/v3/characters/{user_ign}/expectation", name="/v3/expectation")

    @tag('v2') # V2 태그 설정
    @task
    def test_v2_expectation_legacy(self):
        user_ign = random.choice(self.user_names)
        self.client.get(f"/api/v2/characters/{user_ign}/expectation", name="/v2/expectation")

    @tag('v2_like')
    @task
    def test_v2_like_buffering(self):
        user_ign = random.choice(self.user_names)
        self.client.post(f"/api/v2/characters/{user_ign}/like", name="/v2/like")


class LikeSyncLoadTest(HttpUser):
    """
    Issue #147 DoD 검증용 부하 테스트

    목표: 1,000 TPS 부하 중 동기화 작업 시 유실률 0%

    사용법:
        export NEXON_API_KEY="your_api_key"
        export LOGIN_IGN="your_character_name"
        locust -f locustfile.py --tags like_sync_test -u 50 -r 10 -t 60s
    """
    wait_time = between(0.1, 0.5)  # 고부하를 위해 짧은 대기 시간

    # 좋아요 대상 캐릭터들
    target_characters = ["아델", "진격캐넌", "글자", "뉴비렌붕잉", "고딩", "물주", "쯔단", "강은호", "팀에이컴퍼니", "흡혈", "꾸장"]

    def on_start(self):
        """테스트 시작 시 로그인하여 JWT 토큰 획득"""
        api_key = os.environ.get("NEXON_API_KEY")
        login_ign = os.environ.get("LOGIN_IGN", "긱델")

        if not api_key:
            print("[Locust] ERROR: NEXON_API_KEY environment variable not set!")
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
                    print(f"[Locust] Login successful, token acquired")
                else:
                    print(f"[Locust] Login response missing accessToken: {data}")
            else:
                print(f"[Locust] Login failed: {data}")
        else:
            print(f"[Locust] Login HTTP error: {response.status_code} - {response.text}")
            self.token = None

    @tag('like_sync_test')
    @task
    def test_like_with_auth(self):
        """인증된 좋아요 요청 (Issue #147 DoD 검증)"""
        if not hasattr(self, 'token') or not self.token:
            return

        user_ign = random.choice(self.target_characters)
        self.client.post(
            f"/api/v2/characters/{user_ign}/like",
            name="/v2/like (authenticated)"
        )
