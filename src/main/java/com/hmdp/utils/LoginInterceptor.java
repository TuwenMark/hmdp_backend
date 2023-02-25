package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @program: hm-dianping
 * @description: 登录校验拦截器
 * @author: Mr.Ye
 * @create: 2022-10-26 06:14
 **/
public class LoginInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		// 判断ThreadLocal中是否有用户
		if (UserHolder.getUser() == null) {
			// 没有则拦截
			response.setStatus(401);
			return false;
		}
		// 5. 放行
		return true;
	}
}
