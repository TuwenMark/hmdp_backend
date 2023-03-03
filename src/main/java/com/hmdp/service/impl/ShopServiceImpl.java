package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.RedisDataDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Resource
	private CacheClient cacheClient;

	public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	@Override
	public Result queryById(Long id) {
		// 利用互斥锁解决缓存击穿问题
		// Shop shop = queryByIdThroughMutex(id);
		// Shop shop = cacheClient.queryWithMutex(RedisConstants.CACHE_SHOP_KEY,
		// RedisConstants.LOCK_SHOP_KEY, id, Shop.class,
		// (this::getById), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
		// 利用逻辑过期解决缓存击穿问题
		// Shop shop = this.queryByIdThroughLogicExpiration(id);
		Shop shop = cacheClient.querywithLogicExpiration(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY,
				id, Shop.class,
				(this::getById), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
		if (shop == null) {
			return Result.fail("店铺不存在！");
		}
		// 11. 返回店铺信息
		return Result.ok(shop);
	}

	/**
	 * 利用互斥锁解决缓存击穿问题来查询店铺
	 *
	 * @param id 店铺id
	 * @return 店铺信息
	 */
	private Shop queryByIdThroughMutex(Long id) {
		// 1. 查询缓存数据
		ValueOperations<String, String> stringValueOperations = stringRedisTemplate.opsForValue();
		String shopCacheKey = RedisConstants.CACHE_SHOP_KEY + id;
		String shopJson = stringValueOperations.get(shopCacheKey);
		// 2. 缓存存在，直接返回
		if (StrUtil.isNotBlank(shopJson)) {
			Shop shop = JSONUtil.toBean(shopJson, Shop.class);
			return shop;
		}
		// 缓存命中但是空数据，说明是防止缓存穿透的数据，直接返回错误信息
		if (shopJson != null) {
			return null;
		}
		// 3. 缓存不存在获取锁(解决缓存击穿)
		Shop shop = null;
		try {
			Boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
			if (!isLock) {
				// 4. 获取锁失败，休眠并重试
				Thread.sleep(50);
				return queryByIdThroughMutex(id);
			}
			// 5. 获取锁成功，重新查询缓存（防止高并发场景恰好锁被释放和获取）
			shopJson = stringValueOperations.get(RedisConstants.CACHE_SHOP_KEY + id);
			// 6. 缓存存在，说明已经有线程恰好重建好了，直接返回
			if (StrUtil.isNotBlank(shopJson)) {
				shop = JSONUtil.toBean(shopJson, Shop.class);
				return shop;
			}
			// 7. 缓存中不存在，查询数据库
			shop = getById(id);
			// 8. 数据库中不存在，返回错误
			if (shop == null) {
				// 将空值写入Redis，避免缓存穿透
				stringValueOperations.set(shopCacheKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
				return null;
			}
			// 9. 数据库中存在，写入缓存，设置超时时间兜底
			stringValueOperations.set(shopCacheKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL,
					TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		} finally {
			// 10. 释放锁
			unlock(RedisConstants.LOCK_SHOP_KEY + id);
		}
		return shop;
	}

	/**
	 * 利用逻辑过期解决缓存击穿问题来查询店铺
	 *
	 * @param id 店铺id
	 * @return 店铺信息
	 */
	private Shop queryByIdThroughLogicExpiration(Long id) {
		// 1. 查询缓存数据
		ValueOperations<String, String> stringValueOperations = stringRedisTemplate.opsForValue();
		String shopCacheKey = RedisConstants.CACHE_SHOP_KEY + id;
		String redisDataDTOJson = stringValueOperations.get(shopCacheKey);
		// 2. 缓存不存在，直接返回null
		if (StrUtil.isBlank(redisDataDTOJson)) {
			return null;
		}
		// 3. 缓存存在
		// 3.1 获取过期时间
		RedisDataDTO redisDataDTO = JSONUtil.toBean(redisDataDTOJson, RedisDataDTO.class);
		Shop shop = JSONUtil.toBean((JSONObject) redisDataDTO.getData(), Shop.class);
		LocalDateTime expireTime = redisDataDTO.getExpireTime();
		// 3.2 判断是否过期
		if (expireTime.isAfter(LocalDateTime.now())) {
			// 3.3 未过期，直接返回店铺数据
			return shop;
		}
		// 3.4 已过期，进行缓存重建
		// 5. 缓存重建
		// 5.1 获取互斥锁
		String key = RedisConstants.LOCK_SHOP_KEY + id;
		Boolean isLock = tryLock(key);
		// 5.2 判断是否获取到锁
		if (isLock) {
			// 5.3 二次查询缓存是否过期，防止恰好重建完数据获取到锁
			redisDataDTOJson = stringValueOperations.get(shopCacheKey);
			redisDataDTO = JSONUtil.toBean(redisDataDTOJson, RedisDataDTO.class);
			shop = JSONUtil.toBean((JSONObject) redisDataDTO.getData(), Shop.class);
			expireTime = redisDataDTO.getExpireTime();
			// 判断是否过期
			if (expireTime.isAfter(LocalDateTime.now())) {
				// 未过期，释放锁，直接返回店铺数据
				unlock(key);
				return shop;
			}
			// 5.4 已获取到锁，开启独立线程进行缓存重建
			CACHE_REBUILD_EXECUTOR.submit(() -> {
				try {
					saveShop2Redis(id, RedisConstants.CACHE_SHOP_TTL);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				} finally {
					// 5.5 释放锁
					unlock(key);
				}
			});
		}
		// 6. 返回旧的店铺数据
		return shop;
	}

	/**
	 * 缓存预热 / 缓存重建
	 *
	 * @param id       店铺id
	 * @param duration 逻辑过期时间
	 */
	public void saveShop2Redis(Long id, Long duration) throws InterruptedException {
		// 1. 从数据库查询店铺信息
		Shop shop = getById(id);
		// 2. 构造Redis数据
		LocalDateTime expireTime = LocalDateTime.now().plusSeconds(duration);
		RedisDataDTO<Shop> shopRedisDataDTO = new RedisDataDTO<>(shop, expireTime);
		Thread.sleep(200);
		// 3. 将数据存入Redis
		stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shopRedisDataDTO));
	}

	/**
	 * 获取锁
	 *
	 * @param key 锁的Redis key
	 * @return 是否获取成功
	 */
	private Boolean tryLock(String key) {
		Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, RedisConstants.LOCK_SHOP_VALUE,
				RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
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

	@Override
	@Transactional
	public Result update(Shop shop) {
		// 1. 更新数据库
		Long id = shop.getId();
		if (id == null || id <= 0) {
			return Result.fail("店铺id不能为空或不存在！");
		}
		updateById(shop);
		// 2. 删除缓存
		stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
		return Result.ok();
	}

	// @Scheduled(cron = "0 27 22 * * ?")
	protected void shopInfoCacheWarmUp() {
		System.out.println("店铺缓存预热开始！");
		List<Shop> shopList = query().list();
		Iterator<Shop> iterator = shopList.iterator();
		ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
		while (iterator.hasNext()) {
			Shop shop = iterator.next();
			RedisDataDTO redisDataDTO = new RedisDataDTO(shop, LocalDateTime.now().plusSeconds(RedisConstants.CACHE_SHOP_TTL));
			operations.set(RedisConstants.CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(redisDataDTO));
		}
		System.out.println("店铺缓存预热结束！");
	}

}
