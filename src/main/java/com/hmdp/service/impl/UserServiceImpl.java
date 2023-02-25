package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Override
	public Result sendCode(String phone, HttpSession session) {
		// 1. 校验手机号
		if (RegexUtils.isPhoneInvalid(phone)) {
			return Result.fail("手机号格式错误！");
		}
		// 2. 生成验证码
		String code = RandomUtil.randomNumbers(6);
		// 3. 保存验证码到Redis
		stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
		// 4. 发送验证码
		log.debug("发送验证码成功，验证码；{}", code);
		// 5. 返回信息
		return Result.ok();
	}

	@Override
	public Result login(LoginFormDTO loginForm) {
		// 1. 校验手机号
		String phone = loginForm.getPhone();
		if (RegexUtils.isPhoneInvalid(phone)) {
			return Result.fail("手机号格式错误！");
		}
		// 2. 校验验证码
		if (!loginForm.getCode().equals(stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone))) {
			return Result.fail("手机号或验证码有误！");
		}
		// 3. 根据手机号查询用户
		User user = this.query().eq("phone", phone).one();
		// 4. 用户不存在，创建用户并存入数据库
		if (user == null) {
			user = createNewUser(phone);
		}
		// 5. 生成Token
		String token = UUID.randomUUID().toString(true);
		// 6. 将用户存入Redis
		UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
		Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
				.setIgnoreNullValue(true)
				.setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
		String userKey = RedisConstants.LOGIN_USER_KEY + token;
		stringRedisTemplate.opsForHash().putAll(userKey, userMap);
		stringRedisTemplate.expire(userKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
		// 7. 返回token
		return Result.ok(token);
	}

	private User createNewUser(String phone) {
		User user = new User();
		user.setPhone(phone);
		String nickName = USER_NICK_NAME_PREFIX + RandomUtil.randomString(10);
		user.setNickName(nickName);
		this.save(user);
		return user;
	}
}
