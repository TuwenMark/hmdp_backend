package com.hmdp.utils;

/**
 * @program: hm-dianping-backend
 * @description: 分布式锁的接口
 * @author: Mr.Ye
 * @create: 2023-02-15 22:37
 **/
public interface ILock {

	/**
	 * 尝试获取锁
	 *
	 * @param timeoutSec 锁过期的时间，单位秒
	 * @return 是否成功获取到锁
	 */
	Boolean tryLock(Long timeoutSec);

	void unlock();
}
