local idempotencyKey = KEYS[1]
local resourceCapacityKey = KEYS[2]
local holdKey = KEYS[3]

local quantity = tonumber(ARGV[1])
local ttlSeconds = tonumber(ARGV[2])
local resourceId = ARGV[3]
local userId = ARGV[4]
local rawIdempotencyKey = ARGV[5]

-- 1. Check idempotency first
if redis.call('EXISTS', idempotencyKey) == 1 then
    local existingPayload = redis.call('GET', idempotencyKey)
    return {'IDEMPOTENT', existingPayload or ''}
end

-- 2. Validate quantity
if not quantity or quantity <= 0 then
    return {'INVALID_QUANTITY', 'Requested quantity must be greater than zero'}
end

-- 3. Check resource existence
if redis.call('EXISTS', resourceCapacityKey) == 0 then
    return {'RESOURCE_NOT_FOUND', 'Resource not initialized in Redis'}
end

-- 4. Check available capacity
local currentCapacity = tonumber(redis.call('GET', resourceCapacityKey))
if not currentCapacity or currentCapacity < quantity then
    return {'INSUFFICIENT_CAPACITY', tostring(currentCapacity or 0)}
end

-- 5. Decrement capacity
local remainingCapacity = redis.call('DECRBY', resourceCapacityKey, quantity)

-- 6. Build hold payload
local payload = cjson.encode({
    resourceId = resourceId,
    userId = userId,
    quantity = quantity,
    idempotencyKey = rawIdempotencyKey,
    status = 'HELD'
})

-- 7. Store hold and idempotency record with TTL
redis.call('SETEX', idempotencyKey, ttlSeconds, payload)
redis.call('SETEX', holdKey, ttlSeconds, payload)

return {'NEWLY_ADMITTED', payload, tostring(remainingCapacity)}
