local capacityKey = KEYS[1]
local quantity = tonumber(ARGV[1])
local maxCapacity = tonumber(ARGV[2])

if not quantity or quantity <= 0 then
    local current = redis.call('GET', capacityKey)
    return tonumber(current) or 0
end

local current = tonumber(redis.call('GET', capacityKey))
if not current then
    return -1
end

local newCapacity = current + quantity
if maxCapacity and maxCapacity > 0 and newCapacity > maxCapacity then
    newCapacity = maxCapacity
end

redis.call('SET', capacityKey, tostring(newCapacity))
return newCapacity

