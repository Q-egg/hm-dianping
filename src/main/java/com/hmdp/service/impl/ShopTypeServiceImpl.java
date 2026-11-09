package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺类型
     * @return
     */
    public Result queryShopList() {
        String key = CACHE_SHOP_TYPE_KEY;
        //1.从redis中查询店铺类型
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        //2.如果存在，直接返回
        if (StrUtil.isNotBlank(shopTypeJson)) {
            return Result.ok(JSONUtil.toList(shopTypeJson,ShopType.class));
        }

        //3.不存在，查询数据库
        List<ShopType> shopType = query().orderByAsc("sort").list();

        //4.不存在，返回报错信息
        if(shopType == null || shopType.isEmpty()){
            return Result.fail("店铺类型出错了");
        }

        //5.存在，将店铺类型写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopType));

        //6.返回数据

        return Result.ok(shopType);
    }
}
