-- Rate Limit Check Lua Script (Token + Leaky Bucket Hybrid)
--
-- Two-bucket rate limiting algorithm combining:
-- 1. Token Bucket - Burst allowance tracking
-- 2. Leaky Bucket - Sustained rate limiting
--
-- Uses Redis Cluster Hash Tag {userId} for slot affinity.
--
-- KEYS[1] = {event:rate}:{userId}               - Hash storing rate limit state
-- ARGV[1] = requests                              - Number of tokens requested
-- ARGV[2] = capacity                              - Token bucket capacity (burst size)
-- ARGV[3] = refillRate                           - Tokens per second (sustained rate)
-- ARGV[4] = currentTimeSeconds                     - Current timestamp (seconds)
-- ARGV[5] = ttlSeconds                            - TTL for state cleanup
--
-- Returns: Table with status and metadata
--   1 = "ALLOWED" or "REJECTED"
--   2 = remainingTokens - Tokens left in bucket
--   3 = retryAfterSeconds - Seconds until next token (0 if allowed)

local rateKey = KEYS[1]
local requests = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refillRate = tonumber(ARGV[3])
local currentTime = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])

-- Get current state or initialize
local state = redis.call('HMGET', rateKey, 'tokens', 'lastRefill')

local currentTokens = tonumber(state[1])
local lastRefill = tonumber(state[2])

-- Initialize if first request
if not currentTokens then
    currentTokens = capacity
    lastRefill = currentTime
end

-- Calculate token refill based on time elapsed
local timeElapsed = currentTime - lastRefill
local tokensToAdd = timeElapsed * refillRate

-- Refill tokens (cap at capacity)
currentTokens = math.min(capacity, currentTokens + tokensToAdd)

-- Check if request can be allowed
local allowed = 0
local retryAfter = 0

if currentTokens >= requests then
    -- Sufficient tokens - allow request
    currentTokens = currentTokens - requests
    allowed = 1
    retryAfter = 0
else
    -- Insufficient tokens - reject request
    allowed = 0
    -- Calculate retry time based on token deficit
    local deficit = requests - currentTokens
    retryAfter = math.ceil(deficit / refillRate)
end

-- Update state
redis.call('HMSET', rateKey, 'tokens', currentTokens, 'lastRefill', currentTime)

-- Set TTL for cleanup (safety for inactive users)
redis.call('EXPIRE', rateKey, ttlSeconds)

local status = allowed == 1 and 'ALLOWED' or 'REJECTED'
return {status, currentTokens, retryAfter}
