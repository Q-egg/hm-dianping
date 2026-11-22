package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    @Test
    public void loadShopData(){
        //1.查询店铺信息
        List<Shop> list = shopService.list();

        //2.把店铺按照typeId进行分组，一致的放到同一个集合
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        //3.分批写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //获取类型id
            Long typeId = entry.getKey();
            //获取同类型的店铺集合
            List<Shop> shops = entry.getValue();
            String key = SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            //写入redis
            for (Shop shop : shops) {
               locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
               //单个写入redis
//                stringRedisTemplate.opsForGeo().add(key,new Point(x,y),shopId.toString());
            }
            //批量写入redis
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

    @Test
    public void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for(int i = 0;i < 1000000;i++){
            j = i % 1000;
            values[j] = "user:" + i;
            if(j == 999){
                stringRedisTemplate.opsForHyperLogLog().add("user",values);
            }
        }
        Long size = stringRedisTemplate.opsForHyperLogLog().size("user");
        System.out.println(size);
    }



}
