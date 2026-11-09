package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit)
    {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.根据id从redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.存在，则直接返回
        if (StrUtil.isNotBlank(json)) { //isNotBlank()只有真正有字符时才为true，例如"123"
            return JSONUtil.toBean(json,type);
        }

        //判断命中的是否为空字符串
        if(json != null){
            return null;
        }

        //3.不存在，则查询数据库
        R r = dbFallback.apply(id);

        //4.数据库不存在，返回错误信息
        if(r == null){
            //  将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //5.存在，则将数据写入到redis,设定超时时间
        long randomTTL = RandomUtil.randomLong(0, 5);
        this.set(key,r,time + randomTTL,unit);

        //返回
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     */
    //定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = CACHE_SHOP_KEY + id;
        //1.根据id从redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.未命中，直接返回
        if (StrUtil.isBlank(json)) {
            return null;
        }

        //3.命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //4.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //4.1未过期，返回商铺信息
            return r;
        }

        //4.2已过期，需要缓存重建

        //5.缓存重建
        //5.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //5.2判断是否获取锁成功
        if(isLock){

            //二次检查redis中缓存是否过期
            String shopJsonAgain = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(shopJsonAgain)) {
                //释放锁
                unLock(lockKey);
                return null;
            }
            RedisData redisData2 = JSONUtil.toBean(shopJsonAgain, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData2.getData(), type);
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if(expireTime2.isAfter(LocalDateTime.now())){
                //释放锁
                unLock(lockKey);
                return r;
            }

            //5.3获取成功，开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    //1.查询数据库
                    R r1 = dbFallback.apply(id);
                    //2.写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }


        //5.4获取失败，返回过期的商铺信息
        return r;
    }


    /**
     * 解决缓存击穿问题，获取锁
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue((flag));
    }

    /**
     * 解决缓存击穿问题，释放锁
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
