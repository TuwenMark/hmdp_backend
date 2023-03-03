package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

	@Resource
	private ISeckillVoucherService iSeckillVoucherService;

	@Resource
	private RedisIdWorker redisIdWorker;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Resource
	private RedissonClient redissonClient;

	private static final DefaultRedisScript<Long> DEFAULT_REDIS_SCRIPT;

	private IVoucherOrderService proxy;

	/**
	 * 订单的阻塞队列
	 */
	// private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

	/**
	 * 执行队列任务的线程池
	 */
	public static final ExecutorService ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


	static {
		DEFAULT_REDIS_SCRIPT = new DefaultRedisScript();
		// DEFAULT_REDIS_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
		DEFAULT_REDIS_SCRIPT.setLocation(new ClassPathResource("seckillRedis.lua"));
		DEFAULT_REDIS_SCRIPT.setResultType(Long.class);
	}

	/**
	 * 类初始化之后开启线程，执行任务
	 */
	@PostConstruct
	private void init() {
		ORDER_EXECUTOR.submit(new VoucherOrderHandler());
	}

	private class VoucherOrderHandler implements Runnable {
		/**
		 * 基于Redis的队列任务
		 */
		@Override
		public void run() {
			StreamOperations<String, Object, Object> streamOperations = stringRedisTemplate.opsForStream();
			while (true) {
				try {
					// 1. 获取Redis队列消息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
					List<MapRecord<String, Object, Object>> orderList = streamOperations.read(Consumer.from("g1", "c1"),
							StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
							StreamOffset.create(RedisConstants.STREAM_ORDER_KEY, ReadOffset.lastConsumed()));
					// 2. 判断是否获取成功
					if (orderList == null || orderList.isEmpty()) {
						// 队列中没有消息
						Thread.sleep(20);
						continue;
					}
					// 3. 处理消息
					// 3.1. 获取消息，并将消息转换成订单对象
					// 解析消息中的订单信息 String为消息的id Object Object 为消息的k-v键值对
					MapRecord<String, Object, Object> entry = orderList.get(0);
					Map<Object, Object> orderMap = entry.getValue();
					VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(orderMap, new VoucherOrder(), true);
					// 3.2 下单
					handleVoucherOrder(voucherOrder);
					// 4. 确认已消费 SACK KEY GROUP ID
					streamOperations.acknowledge(RedisConstants.STREAM_ORDER_KEY, "g1", entry.getId());
				} catch (Exception e) {
					log.error("异步创建订单异常！", e);
					// 消息处理时出现异常，会把消息放到Redis的pending-list中,因此需要处理异常的消息
					handlePendingList();
				}
			}
		}

		// /**
		//  * 基于阻塞队列的线程任务
		//  */
		// @Override
		// public void run() {
		// 	while (true) {
		// 		try {
		// 			// 获取队列中的订单信息
		// 			VoucherOrder voucherOrder = orderTasks.take();
		// 			// 创建订单
		// 			handleVoucherOrder(voucherOrder);
		// 		} catch (Exception e) {
		// 			log.error("异步创建订单失败！");
		// 		}
		// 	}
		// }
	}

	private void handlePendingList() {
		StreamOperations<String, Object, Object> streamOperations = stringRedisTemplate.opsForStream();
		while (true) {
			try {
				// 1. 获取Redis的PendingList消息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
				List<MapRecord<String, Object, Object>> orderList = streamOperations.read(Consumer.from("g1", "c1"),
						StreamReadOptions.empty().count(1),
						StreamOffset.create(RedisConstants.STREAM_ORDER_KEY, ReadOffset.from("0")));
				// 2. 判断是否获取成功
				if (orderList == null || orderList.isEmpty()) {
					// 队列中没有消息
					Thread.sleep(20);
					break;
				}
				// 3. 处理消息
				// 3.1. 获取消息，并将消息转换成订单对象
				// 解析消息中的订单信息 String为消息的id Object Object 为消息的k-v键值对
				MapRecord<String, Object, Object> entry = orderList.get(0);
				Map<Object, Object> orderMap = entry.getValue();
				VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(orderMap, new VoucherOrder(), true);
				// 3.2 下单
				handleVoucherOrder(voucherOrder);
				// 4. 确认已消费 SACK KEY GROUP ID
				streamOperations.acknowledge(RedisConstants.STREAM_ORDER_KEY, "g1", entry.getId());
			} catch (Exception e) {
				log.error("处理pending-list订单异常！", e);
				try {
					TimeUnit.MILLISECONDS.sleep(20);
				} catch (InterruptedException ex) {
					throw new RuntimeException(ex);
				}
			}
		}
	}

	/**
	 * 基于Redis的Stream队列异步下单
	 *
	 * @param voucherId 优惠券ID
	 * @return 秒杀结果
	 */
	@Override
	public Result seckillVoucher(Long voucherId) {
		// 获取用户ID
		Long userId = UserHolder.getUser().getId();
		// 获取订单ID
		Long orderId = redisIdWorker.nextId("order");
		// 1. 执行脚本，判断购买资格和库存
		Long result = stringRedisTemplate.execute(
				DEFAULT_REDIS_SCRIPT,
				Collections.emptyList(),
				voucherId.toString(),
				userId.toString(),
				orderId.toString()
		);
		// 2. 判断返回结果
		if (result != 0) {
			// 2.1 不为0，无购买资格，报错
			return Result.fail(result == 1 ? "库存不足" : "请勿重复下单");
		}
		// 拿到代理对象进行方法调用，避免事务失效
		proxy = (IVoucherOrderService) AopContext.currentProxy();
		// 3. 返回订单ID
		return Result.ok(orderId);
	}

	/**
	 * 基于队列处理线程中的秒杀订单
	 *
	 * @param voucherOrder 秒杀优惠券订单
	 */
	private void handleVoucherOrder(VoucherOrder voucherOrder) {
		// 获取用户
		Long userId = voucherOrder.getUserId();
		// 获取锁
		RLock lock = redissonClient.getLock(RedisConstants.SECKILL_ORDER_KEY + userId);
		boolean result = lock.tryLock();
		if (!result) {
			log.error("请勿重复下单！");
		}
		// 创建订单
		try {
			// 由于是同一类的方法调用，默认是this当前对象，会导致事务失效。需要使用当前类的代理对象
			// 默认是通过JDK动态代理，基于接口，所以这里需要在接口中创建对应方法。而CGLib动态代理基于类。
			proxy.createVoucherOrder(voucherOrder);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 基于队列创建订单
	 *
	 * @param voucherOrder 订单
	 */
	@Transactional
	@Override
	public void createVoucherOrder(VoucherOrder voucherOrder) {
		// 判断一人一单
		Long voucherId = voucherOrder.getVoucherId();
		Integer count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherId).count();
		if (count > 0) {
			log.error("每位用户只能购买一次！");
		}
		// 5. 扣减库存
		boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
		if (!success) {
			log.error("库存不足！");
		}
		// 6. 创建订单
		save(voucherOrder);
	}

	/**
	 * 基于阻塞队列异步下单
	 *
	 * @param voucherId 优惠券ID
	 * @return 秒杀结果
	 */
	// @Override
	// public Result seckillVoucher(Long voucherId) {
	// 	Long userId = UserHolder.getUser().getId();
	// 	// 1. 执行脚本，判断购买资格和库存
	// 	Long result = stringRedisTemplate.execute(
	// 			DEFAULT_REDIS_SCRIPT,
	// 			Collections.emptyList(),
	// 			voucherId.toString(),
	// 			userId.toString()
	// 	);
	// 	// 2. 判断返回结果
	// 	if (result != 0) {
	// 		// 2.1 不为0，无购买资格，报错
	// 		return Result.fail(result == 1 ? "库存不足" : "请勿重复下单");
	// 	}
	// 	// 2.2 为0，有购买资格，将下单信息保存到阻塞队列
	// 	// 2.2.1 创建订单
	// 	VoucherOrder voucherOrder = new VoucherOrder();
	// 	Long orderId = redisIdWorker.nextId("order");
	// 	voucherOrder.setId(orderId);
	// 	voucherOrder.setUserId(userId);
	// 	voucherOrder.setVoucherId(voucherId);
	// 	// 2.2.2 将任务放入阻塞队列
	// 	boolean offer = orderTasks.offer(voucherOrder);
	// 	if (!offer) {
	// 		Result.fail("当前业务繁忙，请稍后重试！");
	// 	}
	// 	proxy = (IVoucherOrderService) AopContext.currentProxy();
	// 	// 3. 返回订单ID
	// 	return Result.ok(orderId);
	// }

	/**
	 * 同步下单
	 *
	 * @param voucherId 优惠券ID
	 * @return 秒杀结果
	 */
// 	@Override
// 	public Result seckillVoucher(Long voucherId) {
// 		// 1. 查询优惠券信息
// 		SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
// 		if (voucher == null) {
// 			return Result.fail("优惠券不存在！");
// 		}
// 		// 2. 判断秒杀是否开始，是否结束
// 		if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())) {
// 			return Result.fail("当前不在活动时间内！");
// 		}
// 		// 3. 判断库存是否充足
// 		if (voucher.getStock() < 1) {
// 			return Result.fail("库存不足！");
// 		}
		// 每个请求都是一个全新的Long对象
// 		Long userId = UserHolder.getUser().getId();
// 		// 单机部署：根据用户ID加悲观锁
// //		synchronized (userId.toString().intern()) {
// //			IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
// //			return proxy.createVoucherOrder(voucherId, userId);
// //		}
// 		// 分布式部署
// 		// SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
// 		// Boolean flag = lock.tryLock(1200L);
// 		RLock lock = redissonClient.getLock(RedisConstants.SECKILL_ORDER_KEY + userId);
// 		// 默认等待时间-1不重试，释放时间是30秒
// 		boolean flag = lock.tryLock();
// 		if (!Boolean.TRUE.equals(flag)) {
// 			return Result.fail("用户不允许重复下单！");
// 		}
// 		// 一人一单
//		Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//		if (count > 0) {
//		return Result.fail("每个用户只能购买一次！");
//		}
// 		try {
// 			IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
// 			return proxy.createVoucherOrder(voucherId, userId);
// 		} finally {
// 			lock.unlock();
// 		}
// 	}
	@Transactional
	@Override
	public Result createVoucherOrder(Long voucherId, Long userId) {
		// 4. 根据用户ID查询订单是否存在——一人一单
		Integer count = query().eq("user_id", userId).count();
		if (count > 0) {
			return Result.fail("每个用户只能购买一次！");
		}
		// 5. 扣减库存
		boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
		if (!success) {
			Result.fail("库存不足！");
		}
		// 6. 创建订单
		VoucherOrder voucherOrder = new VoucherOrder();
		Long orderId = redisIdWorker.nextId("order");
		voucherOrder.setId(orderId);
		voucherOrder.setUserId(userId);
		voucherOrder.setVoucherId(voucherId);
		save(voucherOrder);
		// 7. 返回订单ID
		return Result.ok(orderId);
	}
}
