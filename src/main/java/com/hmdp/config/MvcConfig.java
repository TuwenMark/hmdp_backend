package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @program: hm-dianping
 * @description: 拦截器配置文件
 * @author: Mr.Ye
 * @create: 2022-10-26 06:20
 **/
@Configuration
public class MvcConfig implements WebMvcConfigurer {
	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 用户登录拦截器
		registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
				"/user/code",
				"/user/login",
				"/blog/hot",
				"/shop-type/**",
				"/shop/**",
				"/upload/**",
				"/voucher/**"
		).order(1);
		// token刷新拦截器
		registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
	}
}
