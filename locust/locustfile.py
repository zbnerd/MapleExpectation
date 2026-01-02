from locust import HttpUser, task, between, tag
import random

class MapleExpectationLoadTest(HttpUser):
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