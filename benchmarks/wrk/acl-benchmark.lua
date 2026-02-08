-- wrk Lua script for ACL Pipeline Performance Benchmark (Issue #300)
--
-- Usage:
--   wrk -t 4 -c 10 -d 30s -s acl-benchmark.lua http://localhost:8080
--
-- Scenarios:
--   - Baseline: Direct DB access (scheduler.nexon-data-collection.enabled=false)
--   - ACL Pipeline: Queue + Batch enabled (scheduler.nexon-data-collection.enabled=true)

-- Request counter for custom metrics
local counter = 0
local total_requests = 0

-- Test IGNs (Korean character names)
local igns = {
  "아델",
  "강은호",
  "진격캐넌",
  "고딩"
}

-- Request initialization
function request()
  -- Select random IGN for each request
  local ign = igns[math.random(1, #igns)]

  -- Construct path: /api/v4/characters/{ign}/expectation
  local path = "/api/v4/characters/" .. ign .. "/expectation"

  -- Return request object
  return wrk.format("GET", path)
end

-- Response handling
function response(status, headers, body)
  total_requests = total_requests + 1

  -- Track status codes
  if status == 200 then
    counter = counter + 1
  end
end

-- Report generation (called at end)
function done(summary, latency, requests)
  -- Print custom metrics
  print("\n=== ACL Pipeline Benchmark Results ===")
  print("Successful requests: " .. counter)
  print("Total requests: " .. total_requests)
  if total_requests > 0 then
    print("Success rate: " .. string.format("%.2f%%", (counter / total_requests) * 100))
  end
  print("\n--- Latency Distribution ---")
  print("Mean: " .. string.format("%.2f ms", latency.mean / 1000))
  print("P50: " .. string.format("%.2f ms", latency:percentile(50) / 1000))
  print("P95: " .. string.format("%.2f ms", latency:percentile(95) / 1000))
  print("P99: " .. string.format("%.2f ms", latency:percentile(99) / 1000))
  print("Max: " .. string.format("%.2f ms", latency.max / 1000))
  print("\n--- Throughput ---")
  if summary.duration > 0 and summary.requests > 0 then
    print("Requests/sec: " .. string.format("%.2f", summary.requests / (summary.duration / 1000000)))
    print("Transfer/sec: " .. string.format("%.2f MB", (summary.bytes / 1024 / 1024) / (summary.duration / 1000000)))
  end
end
