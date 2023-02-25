package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.RedisDataDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @program: hm-dianping-backend
 * @description: Redis缓存工具类
 * @author: Mr.Ye
 * @create: 2023-02-12 13:39
 **/
@Slf4j
@Component
@AllArgsConstructor
public class CacheClient {

	private final StringRedisTemplate stringRedisTemplate;

	public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	/**
	 * 普通存储：数据存入Redis，设置过期时间
	 *
	 * @param key        key
	 * @param value      数据
	 * @param expireTime 过期时间
	 * @param unit       时间单位
	 */
	public void set(String key, String value, Long expireTime, TimeUnit unit) {
		stringRedisTemplate.opsForValue().set(key, value, expireTime, unit);
	}

	/**
	 * 缓存击穿：数据存入Redis，设置逻辑过期时间
	 *
	 * @param key key
	 * @param value 数据对象
	 * @param expireTime 过期时间
	 * @param unit 时间单位
	 * @param <T> 数据类型
	 */
	public <T> void setWithLogicExpire(String key, T value, Long expireTime, TimeUnit unit) {
		// 设置逻辑过期时间
		RedisData redisData = new RedisData(value, LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)));
		// 存入Redis
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
	}

	/**
	 * 查询数据：缓存空对象解决缓存穿透和互斥锁解决缓存击穿
	 *
	 * @param dataKeyPrefix 数据缓存key前缀
	 * @param lockKeyPrefix 互斥锁缓存key前缀
	 * @param id 数据id
	 * @param type 数据类型
	 * @param function 数据库查询函数
	 * @param expireTime 过期时间
	 * @param unit 过期时间单位
	 * @param <R> 返回值类型
	 * @param <T> 参数类型
	 * @return 返回数据
	 */
	public <R, T> R queryWithMutex(String dataKeyPrefix, String lockKeyPrefix, T id, Class<R> type, Function<T, R> function,
									   Long expireTime, TimeUnit unit) {
		// 1. 查询缓存数据
		ValueOperations<String, String> stringValueOperations = stringRedisTemplate.opsForValue();
		String dataKey = dataKeyPrefix + id;
		String json = stringValueOperations.get(dataKey);
		// 2. 缓存存在，直接返回
		if (StrUtil.isNotBlank(json)) {
			R r = JSONUtil.toBean(json, type);
			return r;
		}
		// 缓存命中但是空数据，说明是防止缓存穿透的数据，直接返回错误信息
		if (json != null) {
			return null;
		}
		// 3. 缓存不存在获取锁(解决缓存击穿)
		R r = null;
		String lockKey = lockKeyPrefix + id;
		try {
			Boolean isLock = this.tryLock(lockKey);
			if (!isLock) {
				// 4. 获取锁失败，休眠并重试
				Thread.sleep(50);
				return queryWithMutex(dataKeyPrefix, lockKeyPrefix, id , type, function, expireTime, unit);
			}
			// 5. 获取锁成功，重新查询缓存（防止高并发场景恰好锁被释放和获取）
			json = stringValueOperations.get(dataKey);
			// 6. 缓存存在，说明已经有线程恰好重建好了，直接返回
			if (StrUtil.isNotBlank(json)) {
				r = JSONUtil.toBean(json, type);
				return r;
			}
			// 7. 缓存中不存在，查询数据库
			r = function.apply(id);
			// 8. 数据库中不存在，返回错误
			if (r == null) {
				// 将空值写入Redis，避免缓存穿透
				stringValueOperations.set(dataKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
				return null;
			}
			// 9. 数据库中存在，写入缓存，设置超时时间兜底
			stringValueOperations.set(dataKey, JSONUtil.toJsonStr(r), expireTime, unit);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		} finally {
			// 10. 释放锁
			unlock(lockKey);
		}
		return r;
	}

	/**
	 * 查询数据：利用逻辑过期时间解决热点key的缓存击穿问题
	 *
	 * @param dataKeyPrefix 数据缓存key前缀
	 * @param lockKeyPrefix 互斥锁缓存key前缀
	 * @param id 数据id
	 * @param type 数据类型
	 * @param function 数据库查询函数
	 * @param expireTime 过期时间
	 * @param unit 过期时间单位
	 * @param <R> 返回值类型
	 * @param <T> 参数类型
	 * @return 返回数据
	 */
	public <R, T> R querywithLogicExpiration(String dataKeyPrefix, String lockKeyPrefix, T id, Class<R> type, Function<T, R> function,
										  Long expireTime, TimeUnit unit) {
		// 1. 查询缓存数据
		ValueOperations<String, String> stringValueOperations = stringRedisTemplate.opsForValue();
		String dataKey = dataKeyPrefix + id;
		String redisDataDTOJson = stringValueOperations.get(dataKey);
		// 2. 缓存不存在，直接返回null
		if (StrUtil.isBlank(redisDataDTOJson)) {
			return null;
		}
		// 3. 缓存存在
		// 3.1 获取过期时间
		RedisDataDTO redisDataDTO = JSONUtil.toBean(redisDataDTOJson, RedisDataDTO.class);
		R r = JSONUtil.toBean((JSONObject) redisDataDTO.getData(),type);
		LocalDateTime originExpireTime = redisDataDTO.getExpireTime();
		// 3.2 判断是否过期
		if (originExpireTime.isAfter(LocalDateTime.now())) {
			// 3.3 未过期，直接返回店铺数据
			return r;
		}
		// 3.4 已过期，进行缓存重建
		// 5. 缓存重建
		// 5.1 获取互斥锁
		String lockKey = lockKeyPrefix + id;
		Boolean isLock = tryLock(lockKey);
		// 5.2 判断是否获取到锁
		if (isLock) {
			// 5.3 二次查询缓存是否过期，防止恰好重建完数据获取到锁
			redisDataDTOJson = stringValueOperations.get(dataKey);
			redisDataDTO = JSONUtil.toBean(redisDataDTOJson, RedisDataDTO.class);
			r = JSONUtil.toBean((JSONObject) redisDataDTO.getData(), type);
			originExpireTime = redisDataDTO.getExpireTime();
			// 判断是否过期
			if (originExpireTime.isAfter(LocalDateTime.now())) {
				// 未过期，释放锁，直接返回店铺数据
				unlock(lockKey);
				return r;
			}
			// 5.4 已获取到锁，开启独立线程进行缓存重建
			CACHE_REBUILD_EXECUTOR.submit(() -> {
				try {
					// 查询数据库
					R r1 = function.apply(id);
					// 缓存带有逻辑时间的数据
					this.setWithLogicExpire(dataKey, r1, expireTime, unit);
				} catch (Exception e) {
					throw new RuntimeException(e);
				} finally {
					// 5.5 释放锁
					unlock(lockKey);
				}
			});
		}
		// 6. 返回旧的店铺数据
		return r;
	}

	/**
	 * 获取锁
	 *
	 * @param key 锁的Redis key
	 * @return 是否获取成功
	 */
	private Boolean tryLock(String key) {
		Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, RedisConstants.LOCK_SHOP_VALUE, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
		return BooleanUtil.isTrue(isLock);
	}

	/**
	 * 释放锁
	 *
	 * @param key 锁的Redis key
	 */
	private void unlock(String key) {
		stringRedisTemplate.delete(key);
	}


}
