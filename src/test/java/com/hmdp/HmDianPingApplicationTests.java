package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
	@Resource
	private ShopServiceImpl shopService;

	@Resource
	private RedisIdWorker redisIdWorker;

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

	@Test
	void testTime() {
		System.out.println(LocalDateTime.now());
	}
}
