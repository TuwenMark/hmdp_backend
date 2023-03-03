package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

	@Resource
	private IUserService userService;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Override
	public Result queryHotBlog(Integer current) {
		// 根据用户查询
		Page<Blog> page = query()
				.orderByDesc("liked")
				.page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
		// 获取当前页数据
		List<Blog> records = page.getRecords();
		// 查询用户
		records.forEach(blog -> {
			this.setBlogUser(blog);
			blog.setIsLike(isBlogLiked(blog));
		});
		return Result.ok(records);
	}

	private void setBlogUser(Blog blog) {
		Long userId = blog.getUserId();
		User user = userService.getById(userId);
		blog.setName(user.getNickName());
		blog.setIcon(user.getIcon());
	}

	@Override
	public Result queryBlogById(Long id) {
		// 查询blog
		Blog blog = getById(id);
		if (blog == null) {
			return Result.fail("笔记不存在");
		}
		// 设置blog
		setBlogUser(blog);
		// 查询用户是否被点赞
		blog.setIsLike(isBlogLiked(blog));
		return Result.ok(blog);
	}

	private Boolean isBlogLiked(Blog blog) {
		return stringRedisTemplate.opsForSet().isMember(RedisConstants.BLOG_LIKED_KEY + blog.getId(), blog.getUserId().toString());
	}

	@Override
	public Result likeBlog(Long id) {
		// 参数校验
		if (id == null || id <0) {
			return Result.fail("请求参数错误！");
		}
		// 1. 获取当前登录用户
		UserDTO user = UserHolder.getUser();
		if (user == null || user.getId() < 0) {
			return Result.fail("请重新登录！");
		}
		// 2. 查询笔记是否存在
		Blog blog = getById(id);
		if (blog == null) {
			return Result.fail("当前笔记不存在！");
		}
		// 3. 判断是否点赞
		Long userId = user.getId();
		String likedKey = RedisConstants.BLOG_LIKED_KEY + id;
		SetOperations<String, String> setOptions = stringRedisTemplate.opsForSet();
		if (BooleanUtil.isTrue(setOptions.isMember(likedKey, userId.toString()))) {
			// 4. 已经点赞，取消点赞，点赞数-1
			boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
			if (!isSuccess) {
				return Result.fail("取消点赞失败，请稍后重试！");
			}
			setOptions.remove(likedKey, userId.toString());
			return Result.ok();
		}
		// 5. 未点赞，点赞数+1
		boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
		if (!isSuccess) {
			return Result.fail("点赞失败，请稍后重试！");
		}
		setOptions.add(likedKey, userId.toString());
		return Result.ok();
	}
}
