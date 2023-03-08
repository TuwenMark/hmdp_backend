-- 1. 参数列表
-- 1.1. 优惠券ID
local voucherId = ARGV[1]
-- 1.2. 用户ID
local userId = ARGV[2]
-- 1.3. 订单ID
local orderId = ARGV[3]

-- 2. 数据key
-- 2.1. 秒杀券库存key
local stockKey = 'hmdp:seckill:stock:' .. voucherId
-- 2.2. 秒杀订单key
local orderKey = 'hmdp:seckill:order:' .. voucherId

-- 3. 脚本业务
-- 3.1. 判断库存
if (tonumber(redis.call('GET', stockKey))  <= 0 ) then
    -- 库存不足，返回1
    return 1
end
-- 3.2. 判断用户是否下单（一人一单）
if (redis.call('SISMEMBER', orderKey, userId) == 1) then
    -- 用户重复下单，返回2
    return 2
end
-- 3.3 扣减库存，创建订单（将下单用户加入到优惠券的set集合中，保证唯一下单用户），将订单放入Stream队列
redis.call('INCRBY', stockKey, -1)
redis.call('SADD', orderKey, userId)
redis.call('XADD', 'hmdp:stream.orders', '*', 'voucherId', voucherId, 'userId', userId, 'id', orderId)
return 0