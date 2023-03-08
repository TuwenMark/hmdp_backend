package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
	private IFollowService followService;

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
			this.isBlogLiked(blog);
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
		// 查询用户是否被点赞并设置isLike字段
		this.isBlogLiked(blog);
		return Result.ok(blog);
	}

	private void isBlogLiked(Blog blog) {
		UserDTO user = UserHolder.getUser();
		if (user == null) {
			return;
		}
		Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), user.getId().toString());
		blog.setIsLike(score != null);
	}

	@Override
	public Result likeBlog(Long id) {
		// 1. 参数校验
		if (id == null || id <0) {
			return Result.fail("请求参数错误！");
		}
		// 2. 查询笔记是否存在
		Blog blog = getById(id);
		if (blog == null) {
			return Result.fail("当前笔记不存在！");
		}
		// 3. 判断是否点赞
		Long userId = UserHolder.getUser().getId();
		String likedKey = RedisConstants.BLOG_LIKED_KEY + id;
		ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
		if ((zSetOperations.score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString()) != null)) {
			// 4. 已经点赞，取消点赞，点赞数-1
			boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
			if (!isSuccess) {
				return Result.fail("取消点赞失败，请稍后重试！");
			}
			zSetOperations.remove(likedKey, userId.toString());
			return Result.ok();
		}
		// 5. 未点赞，点赞数+1
		boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
		if (!isSuccess) {
			return Result.fail("点赞失败，请稍后重试！");
		}
		zSetOperations.add(likedKey, userId.toString(), Clock.systemUTC().instant().toEpochMilli());
		return Result.ok();
	}

	@Override
	public Result queryBlogLikes(Long id) {
		// 1. 使用sorted set查询出点赞用户的id ZRANGE KEY START END
		ZSetOperations<String,String> zSetOperations = stringRedisTemplate.opsForZSet();
		Set<String> idSet = zSetOperations.range(RedisConstants.BLOG_LIKED_KEY + id.toString(), 0, 4);
		if (idSet == null || idSet.isEmpty()) {
			return Result.ok(Collections.emptyList());
		}
		List<Long> idList = idSet.stream().map(Long::valueOf).collect(Collectors.toList());
		// 2. 根据id查询出点赞用户
		String idListStr = StrUtil.join(",", idList);
		List<UserDTO> userDTOList = userService.query().in("id", idList).last("order by field (id," + idListStr + ")").list()
			.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
			.collect(Collectors.toList());
		// 3. 去敏返回
		return Result.ok(userDTOList);
	}

	@Override
	public Result saveBlog(Blog blog) {
		// 1. 获取登录用户
		Long userId = UserHolder.getUser().getId();
		blog.setUserId(userId);
		// 2. 保存探店博文
		boolean isSuccess = this.save(blog);
		if (!isSuccess) {
			return Result.fail("笔记发布失败，请稍后重试！");
		}
		// 3. 推送笔记到粉丝、
		// 3.1. 查找粉丝ID
		List<Long> followIdList = followService.query().eq("follow_user_id", userId).list()
				.stream().map(follow -> follow.getUserId()).collect(Collectors.toList());
		// 3.2. 推送笔记ID到每个粉丝的收件箱
		Long blogId = blog.getId();
		followIdList.forEach(id -> stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + id, blogId.toString(), Clock.systemUTC().instant().toEpochMilli()));
		// 返回id
		return Result.ok(blogId);
	}

	@Override
	public Result queryFollowBlogs(Long maxTime, Integer offset) {
		// 1. 获取当前用户
		Long userId = UserHolder.getUser().getId();
		// 2. 查询博客信息
		Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId, 0, maxTime, offset, 2L);
		if (typedTuples == null || typedTuples.isEmpty()) {
			return Result.ok();
		}
		// 3. 解析博客：id列表，最小值（时间戳）
		List<Long> blogIds = new ArrayList<>(typedTuples.size());
		Long minTime = 0L;
		Integer os = 1;
		for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
			blogIds.add(Long.parseLong(typedTuple.getValue()));
			long score = typedTuple.getScore().longValue();
			if (score == minTime) {
				os++;
			} else {
				minTime = typedTuple.getScore().longValue();
				os = 1;
			}
		}
		// 4. 封装信息并返回
		String blogIdsStr = StrUtil.join(",", blogIds);
		List<Blog> blogList = this.query().in("id", blogIds).last("ORDER BY Field (id," + blogIdsStr + ")").list();
		blogList.forEach(blog -> {
			setBlogUser(blog);
			isBlogLiked(blog);
		});
		ScrollResult scrollResult = new ScrollResult();
		scrollResult.setList(blogList);
		scrollResult.setMinTime(minTime);
		scrollResult.setOffset(os);
		return Result.ok(scrollResult);
	}
}
