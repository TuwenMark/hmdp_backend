package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @program: hm-dianping-backend
 * @description: 存入Redis的对象，带有逻辑过期时间
 * @author: Mr.Ye
 * @create: 2023-02-11 22:39
 **/
@Data
@AllArgsConstructor
public class RedisDataDTO<T> {
	/**
	 * 存入Redis的数据
	 */
	private T Data;

	/**
	 * 逻辑过期时间
	 */
	private LocalDateTime expireTime;
}
