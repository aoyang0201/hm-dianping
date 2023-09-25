package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit
    ){
        String key = keyPrefix + id;

        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)){
            //3.存在直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中是否是空值
        if (json != null){
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.数据库也不存在
        if (r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误
            return null;
        }
        //6.存在，写入redis
        this.set(key,r,time,unit);
        //7.返回
        return r;
    }

    private static final ExecutorService CACHE_REBUID_EXECUTOR =
            Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicExpire(
            String keyPrefix,ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit)
    {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)){
            //不存在直接返回null
            return null;
        }
        //存在，json反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期，未过期直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //过期重建缓存
        String lockkey = LOCK_SHOP_KEY + id;
        boolean islock = trylock(lockkey);
        if(islock){
            CACHE_REBUID_EXECUTOR.submit(() -> {
                try {
                    //先查数据库，再写redis
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockkey);
                }
            });
        }
        //7.返回
        return r;
    }

    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
