package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.MQProducerService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    RedissonClient redissonClient;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    private MQProducerService mqProducerService;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

//    String queueName = "stream.orders";

    private IVoucherOrderService proxy;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
//    阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

//    private ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//    @PostConstruct
//    private void init(){
//        SECKILL_ORDER_EXECUTOR.submit(new VoucharOrderHandler());
//    }

//    异步秒杀优化
    public Result seckillVoucher(Long voucherId) {
//        Long orderId = redisIdWorker.nextId("order"); //全局唯一Id
        Long userid = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),//传一个空集合
                voucherId.toString(),
                userid.toString()
//                String.valueOf(orderId)
        );
//      判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

//   如果在lua脚本里已经将订单添加到了Stream消息队列，这一段就不需要了，不过这里尝试用rocketmq
//      为0有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userid);
        voucherOrder.setVoucherId(voucherId);
//      //添加阻塞队列,用jvm得阻塞队列,他其实也有问题，数据不能持久化！
//        orderTasks.add(voucherOrder);

        //放入mq
        String msgBody = JSONUtil.toJsonStr(voucherOrder);
        mqProducerService.sendAsyncMsg(msgBody,"voucherOrder");
        //代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

//    不是异步的秒杀
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

        a.//用redission
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

        b.//用自己定义的Lock
        SimpleRedisLock lock = new SimpleRedisLock("order" + userid, stringRedisTemplate);
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
        }

    }*/

//    利用redis的Stream
/*    private class VoucharOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //ReadOffset.lastConsumed()底层就是 '>'
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //2. 判断消息是否获取成功
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    //3. 消息获取成功之后，我们需要将其转为对象
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                    handleVoucherOrder(voucherOrder);
                    //5. 手动ACK，SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    //订单异常的处理方式我们封装成一个函数，避免代码太臃肿
                    HandlePendingList();
                }
            }
        }
    }

    private void HandlePendingList() {
        while (true) {
            try {
                //1. 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0")));
                //2. 判断pending-list中是否有未处理消息
                if (records == null || records.isEmpty()) {
                    //如果没有就说明没有异常消息，直接结束循环
                    break;
                }
                //3. 消息获取成功之后，我们需要将其转为对象
                MapRecord<String, Object, Object> record = records.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                handleVoucherOrder(voucherOrder);
                //5. 手动ACK，SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.info("处理pending-list异常");
                //如果怕异常多次出现，可以在这里休眠一会儿
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }*/

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
        save(voucherOrder);
    }
}
