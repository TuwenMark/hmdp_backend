-- 从Redis查询标识，和当前线程标识进行对比
if (redis.call("GET", KEYS[1]) == ARGV[1]) then
    -- 如果相同，释放锁
    return redis.call("DEL", KEYS[1])
end
    -- 如果不同，什么也不做，直接返回
    return 0