package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @program: hm-dianping-backend
 * @description: 简单的Redis分布式锁
 * @author: Mr.Ye
 * @create: 2023-02-15 22:40
 **/
public class SimpleRedisLock implements ILock {

	private StringRedisTemplate stringRedisTemplate;

	private String name;

	public static final String PREFIX_LOCK = "lock:";

	public static final String PREFIX_ID = UUID.randomUUID().toString(true) + "-";

	public static final DefaultRedisScript<Long> LOCK_SCRIPT;

	static {
		LOCK_SCRIPT = new DefaultRedisScript<>();
		LOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
		LOCK_SCRIPT.setResultType(Long.class);
	}

	public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
		this.stringRedisTemplate = stringRedisTemplate;
		this.name = name;
	}

	@Override
	public Boolean tryLock(Long timeoutSec) {
		// 添加锁的标识
		String threadId = PREFIX_ID + Thread.currentThread().getId();
		Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(PREFIX_LOCK + name, threadId, timeoutSec, TimeUnit.SECONDS);
		return Boolean.TRUE.equals(flag);
	}

	/**
	 * 通过lua脚本查询和释放锁
	 */
	@Override
	public void unlock() {
		// 通过lua脚本达到原子性
		stringRedisTemplate.execute(LOCK_SCRIPT, Collections.singletonList(PREFIX_LOCK + name), PREFIX_ID + Thread.currentThread().getId());
	}

//	/**
//	 * 查询和释放锁分两步，没有原子性。若查询完释放前阻塞导致锁超时释放，可能会释放掉别人的锁
//	 */
//	@Override
//	public void unlock() {
//		// 获取当前线程的标识
//		String threadId = PREFIX_ID + Thread.currentThread().getId();
//		// 获取Redis存的锁的标识
//		String redisId = stringRedisTemplate.opsForValue().get(PREFIX_LOCK + name);
//		// 判断两个ID是否一致，防止超时导致释放了别人的锁
//		if (threadId.equals(redisId)) {
//			stringRedisTemplate.delete(PREFIX_LOCK + name);
//		}
//	}
}
