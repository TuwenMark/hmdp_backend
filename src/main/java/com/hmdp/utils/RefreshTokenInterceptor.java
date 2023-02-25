package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @program: hm-dianping
 * @description: 登录校验拦截器
 * @author: Mr.Ye
 * @create: 2022-10-26 06:14
 **/
public class RefreshTokenInterceptor implements HandlerInterceptor {

	private StringRedisTemplate stringRedisTemplate;

	public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		// 1. 获取Redis中的用户
		String token = request.getHeader(SystemConstants.AUTHORIZATION);
		if (StrUtil.isBlank(token)) {
			return true;
		}
		String userKey = RedisConstants.LOGIN_USER_KEY + token;
		Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(userKey);
		// 2. 判断用户是否存在，不存在则放行
		if (userMap.isEmpty()) {
			return true;
		}
		UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
		// 3. 用户存在，保存在ThreadLocal中
		UserHolder.saveUser(userDTO);
		// 4. 刷新token有效期
		stringRedisTemplate.expire(userKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
		// 5. 放行
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
		UserHolder.removeUser();
	}
}
