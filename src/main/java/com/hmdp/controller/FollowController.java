package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

	@Resource
	private IFollowService followService;

	@Resource
	private IUserService userService;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@GetMapping("/or/not/{id}")
	public Result isFollow(@PathVariable("id") Long followUserId) {
		return followService.isFollow(followUserId);
	}

	@PutMapping("/{id}/{isFollow}")
	public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
		return followService.follow(followUserId, isFollow);
	}

	@GetMapping("/common/{id}")
	public Result commonFollows(@PathVariable("id") Long userId) {
		// 1. 校验
		if (userId == null || userId <= 0) {
			return Result.fail("请求有误，当前用户不存在！");
		}
		// 2. 获取当前用户的ID
		Long currentUserId = UserHolder.getUser().getId();
		// 3. 去Redis中取关注列表的交集
		SetOperations<String, String> setOperations = stringRedisTemplate.opsForSet();
		List<User> userList = setOperations.intersect((RedisConstants.FOLLOW_KEY + currentUserId).toString(), (RedisConstants.FOLLOW_KEY + userId).toString())
				.stream().map(Long::valueOf).map(id -> userService.getById(id)).collect(Collectors.toList());
		if (userList == null || userList.isEmpty()) {
			return Result.ok(Collections.emptyList());
		}
		List<UserDTO> userDTOList = userList.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
		return Result.ok(userDTOList);
	}
}
