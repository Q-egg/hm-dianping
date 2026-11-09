package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    /**
     * 解决缓存击穿问题，获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue((flag));
    }
    */

    /**
     * 解决缓存击穿问题，释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
    */

    /**
     * 根据id查询商户信息
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        //1.随机过期时间解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        
        //2.互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 缓存穿透
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.根据id从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.存在，则直接返回
        if (StrUtil.isNotBlank(shopJson)) { //isNotBlank()只有真正有字符时才为true，例如"123"
            return JSONUtil.toBean(shopJson,Shop.class);
        }

        //判断命中的是否为空字符串
        if(shopJson != null){
            return null;
        }

        //3.不存在，则查询数据库
        Shop shop = getById(id);

        //4.数据库不存在，返回错误信息
        if(shop == null){
            //  将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //5.存在，则将数据写入到redis,设定超时时间
        long randomTTL = RandomUtil.randomLong(0, 5);
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL + randomTTL, TimeUnit.MINUTES);

        //返回
        return shop;
    }
    */

    /**
     * 互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1.根据id从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.存在，则直接返回
        if (StrUtil.isNotBlank(shopJson)) { //isNotBlank()只有真正有字符时才为true，例如"123"
            return JSONUtil.toBean(shopJson,Shop.class);
        }

        //判断命中的是否为空字符串
        if(shopJson != null){
            return null;
        }

        //4.缓存重建
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        // 尝试获取锁
        boolean isLock = tryLock(lockKey);
        // 获取失败：等待并重试，不进入下面的 try/finally
        if (!isLock) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待缓存重建时被中断", e);
            }
            return queryWithMutex(id);
        }

        Shop shop = null;
        try {
            //二次检查redis中是否有该店铺缓存
            String shopJsonAgain = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJsonAgain)) { //isNotBlank()只有真正有字符时才为true，例如"123"
                return JSONUtil.toBean(shopJsonAgain,Shop.class);
            }

            if(shopJsonAgain != null) {
                return null;
            }

            //4.4获取成功，则查数据库，写入redis
            shop = getById(id);

            //4.数据库不存在，返回错误信息
            if(shop == null){
                //  将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //5.存在，则将数据写入到redis,设定超时时间
            long randomTTL = RandomUtil.randomLong(0, 5);
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL + randomTTL, TimeUnit.MINUTES);
        } finally {
            //6.释放互斥锁
            unLock(lockKey);
        }

        
        //返回
        return shop;
    }
     */


//    //定义线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     *逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.根据id从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.未命中，直接返回
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        //3.命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //4.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //4.1未过期，返回商铺信息
            return shop;
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
            shop = JSONUtil.toBean((JSONObject) redisData2.getData(), Shop.class);
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if(expireTime2.isAfter(LocalDateTime.now())){
                //释放锁
                unLock(lockKey);
                return shop;
            }

            //5.3获取成功，开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,1800L);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }


        //5.4获取失败，返回过期的商铺信息
        return shop;
    }
     */

//    private void saveShop2Redis(Long id, Long expireTime){
//        //1.查询数据库
//        Shop shop = getById(id);
//        //2.封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
//
//        //3.写入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
//    }

    /**
     * 更新商铺信息
     */
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
