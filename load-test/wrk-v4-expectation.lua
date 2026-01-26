-- wrk Lua script for V4 Expectation API Load Test
-- #266 ADR 정합성 리팩토링 검증

-- URL encode function for Korean characters
local function urlencode(str)
    if str then
        str = string.gsub(str, "([^%w%-_.~])", function(c)
            return string.format("%%%02X", string.byte(c))
        end)
    end
    return str
end

-- 테스트용 캐릭터 IGN 목록 (URL 인코딩된 형태로 저장)
-- 은월 = %EC%9D%80%EC%9B%94
-- 호영 = %ED%98%B8%EC%98%81
-- 라라 = %EB%9D%BC%EB%9D%BC
local igns = {
    "%EC%9D%80%EC%9B%94",  -- 은월
    "%ED%98%B8%EC%98%81",  -- 호영
    "%EB%9D%BC%EB%9D%BC"   -- 라라
}

local counter = 0

function setup(thread)
    thread:set("id", counter)
    counter = counter + 1
end

function init(args)
    requests = 0
    responses = 0
    errors = 0

    local thread_id = wrk.thread:get("id")
    local ign_index = (thread_id % #igns) + 1
    current_ign = igns[ign_index]
end

function request()
    requests = requests + 1
    local path = "/api/v4/expectation/" .. current_ign
    return wrk.format("GET", path, {
        ["Accept"] = "application/json",
        ["Accept-Encoding"] = "gzip"
    })
end

function response(status, headers, body)
    responses = responses + 1
    if status ~= 200 then
        errors = errors + 1
    end
end

function done(summary, latency, requests)
    io.write("\n")
    io.write("========================================\n")
    io.write("  V4 Expectation API Load Test Results\n")
    io.write("  #266 ADR 정합성 리팩토링 검증\n")
    io.write("========================================\n")
    io.write(string.format("Duration:        %.2f s\n", summary.duration / 1000000))
    io.write(string.format("Total Requests:  %d\n", summary.requests))
    io.write(string.format("Total Bytes:     %.2f MB\n", summary.bytes / 1024 / 1024))
    io.write("----------------------------------------\n")
    io.write(string.format("Requests/sec:    %.2f\n", summary.requests / (summary.duration / 1000000)))
    io.write(string.format("Transfer/sec:    %.2f KB\n", (summary.bytes / (summary.duration / 1000000)) / 1024))
    io.write("----------------------------------------\n")
    io.write("Errors:\n")
    io.write(string.format("  Connect:       %d\n", summary.errors.connect))
    io.write(string.format("  Read:          %d\n", summary.errors.read))
    io.write(string.format("  Write:         %d\n", summary.errors.write))
    io.write(string.format("  Timeout:       %d\n", summary.errors.timeout))
    io.write(string.format("  Status:        %d\n", summary.errors.status))
    io.write("----------------------------------------\n")
    io.write("Latency Distribution:\n")
    io.write(string.format("  50%%:           %.2f ms\n", latency:percentile(50) / 1000))
    io.write(string.format("  75%%:           %.2f ms\n", latency:percentile(75) / 1000))
    io.write(string.format("  90%%:           %.2f ms\n", latency:percentile(90) / 1000))
    io.write(string.format("  99%%:           %.2f ms\n", latency:percentile(99) / 1000))
    io.write(string.format("  Max:           %.2f ms\n", latency.max / 1000))
    io.write("========================================\n")
end
