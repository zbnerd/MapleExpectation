-- wrk Lua script for ACL Pipeline Performance Benchmark (Issue #300)
--
-- Usage:
--   wrk -t 4 -c 10 -d 30s -s acl-benchmark.lua -- <ocid> http://localhost:8080/api/v4/characters/
--
-- Scenarios:
--   - Baseline: Direct DB access (scheduler.nexon-data-collection.enabled=false)
--   - ACL Pipeline: Queue + Batch enabled (scheduler.nexon-data-collection.enabled=true)

-- Request counter for custom metrics
local counter = 0
local requests = 0

-- Request initialization
function request()
  -- Extract OCID from command line argument
  local ocid = wrk.path:match("<ocid>") and arg[1] or "test-character"

  -- Construct path: /api/v4/characters/{ocid}/expectation
  local path = "/api/v4/characters/" .. ocid .. "/expectation"

  -- Return request object
  return wrk.format("GET", path)
end

-- Response handling
function response(status, headers, body)
  requests = requests + 1

  -- Track status codes
  if status == 200 then
    counter = counter + 1
  end

  -- Optionally track latency percentiles here
  -- (wrk handles this automatically with --latency flag)
end

-- Report generation (called at end)
function done(summary, latency, requests)
  -- Print custom metrics
  print("\n=== ACL Pipeline Benchmark Results ===")
  print("Successful requests: " .. counter)
  print("Total requests: " .. requests)
  print("Success rate: " .. string.format("%.2f%%", (counter / requests) * 100))
  print("\n--- Latency Distribution ---")
  print("Mean: " .. string.format("%.2f ms", latency.mean / 1000))
  print("P50: " .. string.format("%.2f ms", latency:percentile(50) / 1000))
  print("P95: " .. string.format("%.2f ms", latency:percentile(95) / 1000))
  print("P99: " .. string.format("%.2f ms", latency:percentile(99) / 1000))
  print("Max: " .. string.format("%.2f ms", latency.max / 1000))
  print("\n--- Throughput ---")
  print("Requests/sec: " .. string.format("%.2f", summary.duration / 1000000 / summary.requests))
end

-- Dynamic OCID rotation (optional)
-- Uncomment to test with multiple OCIDs
local ocids = {
  "character-001",
  "character-002",
  "character-003",
  "character-004",
  "character-005"
}

function request()
  local ocid = ocids[math.random(1, #ocids)]
  local path = "/api/v4/characters/" .. ocid .. "/expectation"
  return wrk.format("GET", path)
end
