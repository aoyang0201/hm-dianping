package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    String name;
    StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private final static String KEY_PREFIX = "lock";

    @Override
    public boolean trylock(long timeoutSec) {
        //获取线程标识
        long threadId = Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue() //加空格是为了转为字符串
                .setIfAbsent(KEY_PREFIX + name, threadId + "",timeoutSec, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
