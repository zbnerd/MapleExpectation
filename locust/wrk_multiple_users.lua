-- wrk_multiple_users.lua
-- V4 API 다중 사용자 벤치마크 스크립트 (#264)

-- 테스트할 닉네임 목록 (URL Encoded)
local users = {
    "%EC%95%84%EB%8D%B8",                           -- 아델
    "%EC%A7%84%EA%B2%A9%EC%BA%90%EB%84%8C",         -- 진격캐넌
    "%EA%B8%80%EC%9E%90",                           -- 글자
    "%EB%89%B4%EB%B9%84%EB%A0%8C%EB%B6%95%EC%9E%89", -- 뉴비렌붕잉
    "%EA%B8%B1%EB%8D%B8",                           -- 긱델
    "%EA%B3%A0%EB%94%A9",                           -- 고딩
    "%EB%AC%BC%EC%A3%BC",                           -- 물주
    "%EC%AF%94%EB%8B%A8",                           -- 쯔단
    "%EA%B0%95%EC%9D%80%ED%98%B8",                   -- 강은호
    "%ED%8C%80%EC%97%90%EC%9D%B4%EC%BB%B4%ED%8D%BC%EB%8B%88", -- 팀에이컴퍼니
    "%ED%9D%A1%ED%98%88",                           -- 흡혈
    "%EA%BE%B8%EC%9E%A5"                            -- 꾸장
}

-- wrk가 요청을 생성할 때마다 호출
request = function()
    -- 랜덤하게 유저 선택
    local user = users[math.random(#users)]
    local path = "/api/v4/characters/" .. user .. "/expectation"

    -- 헤더 설정 (GZIP 필수 - L1 Fast Path)
    local headers = {}
    headers["Accept-Encoding"] = "gzip"

    -- GET 요청 반환
    return wrk.format("GET", path, headers)
end

-- 테스트 완료 시 통계 출력
done = function(summary, latency, requests)
    io.write("------------------------------\n")
    io.write("V4 API Benchmark Summary\n")
    io.write("------------------------------\n")
    io.write(string.format("Total Requests: %d\n", summary.requests))
    io.write(string.format("Total Errors: %d\n", summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.status + summary.errors.timeout))
    io.write(string.format("RPS: %.2f\n", summary.requests / (summary.duration / 1000000)))
    io.write("------------------------------\n")
end
