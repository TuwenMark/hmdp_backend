package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
	@Resource
	private ShopServiceImpl shopService;

	@Resource
	private RedisIdWorker redisIdWorker;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	public final ExecutorService executorService = Executors.newFixedThreadPool(500);

	// @Test
	void testNextId() throws InterruptedException {
		long start = System.currentTimeMillis();
		CountDownLatch latch = new CountDownLatch(300);
		Runnable task = () -> {
			for (int i = 0; i < 100; i++) {
				System.out.println("id=" + redisIdWorker.nextId("order"));
			}
			latch.countDown();
		};
		for (int i = 0; i < 300; i++) {
			executorService.submit(task);
		}
		latch.await();
		long end = System.currentTimeMillis();
		System.out.println("耗时：" + (end - start));
	}

	//	@Test
	void testSaveShop2Redis() throws InterruptedException {
		shopService.saveShop2Redis(1L, 20L);
	}

	// @Test
	void testTime() {
		System.out.println(LocalDateTime.now());
	}

	// @Test
	void nameInsertGEO() {
		// 1. 查询店铺信息并分组   Map<Long, List<Shop>>
		List<Shop> shopList = shopService.list();
		Map<Long, List<Shop>> shopMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
		// 2. 导入店铺信息到Redis  GEOADD key longitude latitude member
		for (Map.Entry<Long, List<Shop>> shopEntry : shopMap.entrySet()) {
			String key = RedisConstants.SHOP_GEO_KEY + shopEntry.getKey().toString();
			List<RedisGeoCommands.GeoLocation<String>> geoLocationList = new ArrayList<>(shopList.size());
			for (Shop shop : shopEntry.getValue()) {
				// 方法一：直接遍历店铺信息写入Redis，与redis建立的连接较多
				// stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
				geoLocationList.add(new RedisGeoCommands.GeoLocation(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
			}
			stringRedisTemplate.opsForGeo().add(key, geoLocationList);
		}
	}
}
