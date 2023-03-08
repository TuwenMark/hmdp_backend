package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

	@Resource
	private IUserService userService;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Override
	public Result isFollow(Long followUserId) {
		// 获取当前登录用户
		UserDTO user = UserHolder.getUser();
		if (user == null || user.getId() < 0) {
			return Result.fail("当前未登录，请登录后再进行操作！");
		}
		// 查询tb_follow数据库是否有关注数据
		Integer count = this.query().eq("user_id", user.getId()).eq("follow_user_id", followUserId).count();
		return Result.ok(count > 0);
	}

	@Override
	public Result follow(Long followUserId, Boolean isFollow) {
		// 判断followUserId
		if (followUserId < 0 || userService.getById(followUserId) == null) {
			return Result.fail("当前用户不存在！");
		}
		// 获取当前登录用户
		UserDTO user = UserHolder.getUser();
		if (user == null || user.getId() < 0) {
			return Result.fail("当前未登录，请登录后再进行操作！");
		}
		Long userId = user.getId();
		SetOperations<String, String> setOperations = stringRedisTemplate.opsForSet();
		String followKey = RedisConstants.FOLLOW_KEY + userId;
		// 判断是关注还是取关
		if (isFollow) {
			// 关注，插入数据
			Follow follow = new Follow().setUserId(userId).setFollowUserId(followUserId);
			boolean isSuccess = this.save(follow);
			if (!isSuccess) {
				return Result.fail("操作失败，请稍后重试！");
			}
			// 写入数据到Redis的set集合
			setOperations.add(followKey, followUserId.toString());
		} else {
			// 取关，删除数据
			boolean isSuccess = this.remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
			if (!isSuccess) {
				return Result.fail("操作失败，请稍后重试！");
			}
			// 从Redis的set集合中删除数据
			setOperations.remove(followKey, followUserId);
		}
		return Result.ok();
	}
}
