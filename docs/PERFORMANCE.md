# 🚀 Performance Benchmark: 로컬 환경 RPS 235 & 에러율 0% 달성

## 📊 1. 최종 부하 테스트 결과 (Summary)
> **"단일 인스턴스(Local)에서 고강도 CPU 연산 작업을 수행하며 안정적인 처리량 확보"**

![Locust Statistics](./images/locust_statistics_260104.png)
*(실제 테스트 결과 스크린샷)*

| Metric | Value | 비고 |
| :--- | :--- | :--- |
| **Total Requests** | **48,183** | 누락 없음 |
| **Failures** | **0%** | **완전 무결성 달성** (Connection Timeout 해결) |
| **RPS (Mean)** | **~235.7** | 초당 요청 처리 수 |
| **Median Latency** | **160 ms** | 50%의 유저는 0.16초 내 응답 |
| **Throughput** | **~82.5 MB/s** | 순수 데이터 처리량 (하단 설명 참조) |

---

## 💡 2. 이것이 왜 "유의미한" 수치인가?
단순히 "RPS 235"라는 숫자보다, **서버가 수행하는 작업의 무게(Weight)**가 중요합니다.
이 테스트는 단순한 DB 조회(I/O Bound)가 아니라, **극한의 CPU 연산(CPU-Bound)** 작업입니다.

### ⚙️ 요청 1건당 처리 프로세스
1. **Decompression:** DB에서 `GZIP` 압축된 **17KB** 바이너리 조회
2. **Expansion:** 메모리 상에서 **350KB** 크기의 Raw JSON으로 압축 해제(Streaming)
3. **Parsing & Calculation:** 거대한 JSON 트리를 파싱하고 비즈니스 로직(기대값 계산) 수행
4. **Serialization:** 최종 결과를 **4.3KB** DTO로 변환하여 응답

### ⚡ 결론: 초당 데이터 처리량
> `235 RPS` × `350 KB` ≈ **82.25 MB/sec**

로컬 PC(단일 인스턴스)에서 **초당 80MB 이상의 텍스트 데이터를 힙 메모리에 올리고 가공**하면서도,
**GC 멈춤(Stop-the-world)이나 DB 커넥션 고갈 없이 0%의 에러율**을 방어해냈습니다.

---

## 📉 3. Before & After (Run #1 vs Run #2)
![Locust Charts](./images/locust_chart_260104.png)

### 🔴 Run #1 (Before Fix)
- **현상:** 그래프 하단의 **빨간색 점(Failures)** 폭발.
- **원인:** Redis 락 획득 실패 시 즉시 MySQL로 Fallback하며 **DB 커넥션 풀(Pool Size 10) 고갈**.
- **결과:** `SQLTransientConnectionException` 발생 및 대기열 폭주.

### 🟢 Run #2 (After Fix)
- **조치:**
    - **Redis Wait Strategy:** 락 경합 시 즉시 튕겨내지 않고 Redis Pub/Sub 대기(Wait).
    - **Connection Pool:** 락 전용 풀 사이즈 증설 (10 -> 50).
- **결과:**
    - **Failures 0 (Flat Red Line):** 단 한 건의 에러도 없이 완벽 방어.
    - **Stable RPS (Green Line):** 출렁임 없이 230~240 RPS 유지.
    - **Users:** 동시 접속자 **500명** 수용 완료.

---

## 🏁 4. Conclusion
이 테스트는 **DB와 Redis가 같은 로컬 머신에서 자원을 경쟁하는 최악의 환경**에서 수행되었습니다.
자원이 격리되고 네트워크 대역폭이 확보된 **실제 운영 환경(AWS)**에서는 이보다 훨씬 높은 퍼포먼스와 안정성을 기대할 수 있습니다.

- **Stability:** ✅ Verified (Zero Failures)
- **Performance:** ✅ Verified (High Throughput CPU Processing)
- **Architecture:** ✅ Validated (Resilient Locking & LogicExecutor)