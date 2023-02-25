package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class RedisData<T> {
	private T data;
	private LocalDateTime expireTime;
}
