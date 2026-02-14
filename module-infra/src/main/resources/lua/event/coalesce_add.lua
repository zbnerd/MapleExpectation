-- Coalesce Add Lua Script
--
-- Atomic operation to coalesce multiple update requests for the same user.
-- Uses Redis Cluster Hash Tag {userId} to ensure all keys are in the same slot.
--
-- KEYS[1] = {event:coalesce}:{userId}        - Hash storing coalesced request data
-- KEYS[2] = {event:coalesce:counter}:{userId} - Counter for coalesced requests
-- ARGV[1] = eventType                          - Type of event (e.g., "CHARACTER_UPDATE")
-- ARGV[2] = eventId                            - Unique event identifier
-- ARGV[3] = eventData                          - Serialized event data
-- ARGV[4] = maxBatchSize                       - Maximum batch size before forcing flush
-- ARGV[5] = ttlSeconds                         - TTL for coalesced data (safety)
--
-- Returns: Table with status and metadata
--   1 = "QUEUED" - Request added to batch
--   2 = batchCount - Current number of requests in batch
--   3 = shouldFlush - Whether batch should be flushed (1=true, 0=false)

local coalesceKey = KEYS[1]
local counterKey = KEYS[2]
local eventType = ARGV[1]
local eventId = ARGV[2]
local eventData = ARGV[3]
local maxBatchSize = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])

-- Check if event with same ID already exists (deduplication)
local existing = redis.call('HGET', coalesceKey, eventId)

if existing then
    -- Duplicate request - return existing data
    return {'DUPLICATE', redis.call('HGET', counterKey, eventType), 0}
end

-- Add event to batch (HSET is atomic)
redis.call('HSET', coalesceKey, eventId, eventData)

-- Increment counter for this event type
local count = redis.call('HINCRBY', counterKey, eventType, 1)

-- Set TTL on first add (safety for orphan keys)
local currentTtl = redis.call('TTL', coalesceKey)
if currentTtl == -1 then
    redis.call('EXPIRE', coalesceKey, ttlSeconds)
    redis.call('EXPIRE', counterKey, ttlSeconds)
end

-- Determine if batch should be flushed
local shouldFlush = 0
if count >= maxBatchSize then
    shouldFlush = 1
end

return {'QUEUED', count, shouldFlush}
