package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    RedissonClient redissonClient;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    private ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucharOrderHandler());
    }
    private class VoucharOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userid = voucherOrder.getUserId();
        RLock redisLock = redissonClient.getLock("order" + userid);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            redisLock.unlock();
        }

    }

    private IVoucherOrderService proxy;

    public Result seckillVoucher(Long voucherId) {
        Long userid = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),//传一个空集合
                voucherId.toString(),userid.toString()
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userid);
        voucherOrder.setVoucherId(voucherId);

        //创建阻塞队列
        orderTasks.add(voucherOrder);
        //代理对象
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠劵
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束404");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1){
            return Result.fail("库存不足");
        }
        Long userid = UserHolder.getUser().getId();
        //用redission
        RLock redisLock = redissonClient.getLock("order" + userid);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            return Result.fail("不允许抢多张优惠券");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            redisLock.unlock();
        }

        //用自己定义的Lock
*//*        SimpleRedisLock lock = new SimpleRedisLock("order" + userid, stringRedisTemplate);
        boolean islock = lock.trylock(10);
        if (!islock){
            return Result.fail("不允许同时下单");
        }
        //获取代理对象
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }*//*
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userid = voucherOrder.getUserId();
        int count = query().eq("user_id", userid).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0 ){
            log.error("用户已经购买过一次");
            return;
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0).update();
        if (!success){
            log.error("库存不足");
            return;
        }

/*        //创建订单(使用异步秒杀之后不需要再创建订单了)
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userid);
        voucherOrder.setVoucherId(voucherOrder);*/
        save(voucherOrder);

    }

}
