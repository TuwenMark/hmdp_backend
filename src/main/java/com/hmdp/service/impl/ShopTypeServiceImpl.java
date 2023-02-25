package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Comparator;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Override
	public Result queryTypeList() {
		// 1. 查询缓存
		ListOperations<String, String> listOperations = stringRedisTemplate.opsForList();
		// 2. 存在，直接返回
		String shopTypeKey = RedisConstants.CACHE_SHOP_TYPE_KEY;
		List<String> shopTypeJsons = listOperations.range(shopTypeKey, 0, -1);
		if (shopTypeJsons != null && !CollectionUtils.isEmpty(shopTypeJsons)) {
			List<ShopType> shopTypes = shopTypeJsons.stream()
					.map(shopTypeJson -> JSONUtil.toBean(shopTypeJson, ShopType.class))
					.sorted(Comparator.comparing(ShopType::getSort))
					.collect(Collectors.toList());
			return Result.ok(shopTypes);
		}
		// 3. 不存在，查询数据库
		List<ShopType> shopTypeList = list();
		// 4. 不存在，返回错误信息
		if (shopTypeList == null || CollectionUtils.isEmpty(shopTypeList)) {
			return Result.fail("数据为空！");
		}
		// 5. 存在，写入缓存
		listOperations.leftPushAll(shopTypeKey, shopTypeList.stream().map(shopType -> JSONUtil.toJsonStr(shopType)).collect(Collectors.toList()));
		// 6. 返回数据
		return Result.ok(shopTypeList);
	}
}
