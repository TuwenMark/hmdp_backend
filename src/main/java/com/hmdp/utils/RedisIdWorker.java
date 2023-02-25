package com.hmdp.utils;

import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @program: hm-dianping-backend
 * @description: 基于Redis的分布式ID生成器
 * @author: Mr.Ye
 * @create: 2023-02-12 22:54
 **/
@Component
@AllArgsConstructor
public class RedisIdWorker {

	private StringRedisTemplate stringRedisTemplate;

	public static final int COUNT_BITS = 32;

	public Long nextId(String keyPrefix) {
		// 1. 生成时间戳：当前时间的秒数
		long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
		// 2. 生成序列号：利用redis自增
		// 2.1 获取当天日期，精确到天
		String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
		// 2.2 自增长
		Long count = stringRedisTemplate.opsForValue().increment("incr:" + keyPrefix + ":" + date);
		// 3. 拼接返回
		return timestamp << COUNT_BITS | count;
	}
}
