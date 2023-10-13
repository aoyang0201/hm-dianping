package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderhandleService;
import com.hmdp.service.MQProducerService;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
@Slf4j
@RocketMQMessageListener(topic = "RLT_TEST_TOPIC", consumerGroup = "voucherOrder_con-group")
public class VoucherOrderhandleServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderhandleService, RocketMQListener<MessageExt> {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    RedissonClient redissonClient;
//    private IVoucherOrderService proxy;

    @Override
    public void onMessage(MessageExt message) {
        String msg = new String(message.getBody());
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        if(message.getReconsumeTimes() == 3){
            log.error("{}消费了3次都消费失败",voucherOrder.toString());
            //消息入库，人工干预
        }
        log.info("voucherOrder:{}",voucherOrder.toString());
        handleVoucherOrder(voucherOrder);
    }

    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userid = voucherOrder.getUserId();
        RLock redisLock = redissonClient.getLock("order" + userid);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            IVoucherOrderhandleService proxy = (IVoucherOrderhandleService) AopContext.currentProxy();
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
