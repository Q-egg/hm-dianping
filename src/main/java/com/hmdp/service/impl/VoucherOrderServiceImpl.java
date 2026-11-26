package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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

    private static final int UNPAID = 1;
    private static final int PAID = 2;
    private static final int REDEEMED = 3;
    private static final int CANCELED = 4;
    private static final int REFUNDED = 6;
    private static final int DEMO_PAY_TYPE = 0;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ObjectProvider<IVoucherOrderService> voucherOrderService;

    @Resource
    private SeckillStockReleaseService seckillStockReleaseService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    //阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //使用内部类执行线程任务
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {

                try {
                    //1.获取消息队列中的订单信息 XGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }

                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.如果获取成功，则可以下单
                    handlerVoucherOrder(voucherOrder);
                    //5.ACK确认 sack streams.orders g1 id
                    RecordId id = record.getId();
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", id);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {

                try {
                    //1.获取pending-list中的订单信息 XGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //2.1如果获取失败，说明pending-list没有消息，结束循环
                        break;
                    }

                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.如果获取成功，则可以下单
                    handlerVoucherOrder(voucherOrder);
                    //5.ACK确认 sack streams.orders g1 id
                    RecordId id = record.getId();
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", id);
                }catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

//    //使用内部类执行线程任务
//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while(true){
//                //1.获取队列中的订单信息
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //2.创建订单
//                    handlerVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }

    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        lock.lock();
        try {
            voucherOrderService.getObject().createOrderVoucher(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    public Result seckillVoucher(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        int r = result.intValue();
        //2.判断结果是否为0
        if(r != 0){
            //3.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足": "无购买资格");
        }
        return Result.ok(orderId);
    }

//    public Result seckillVoucher(Long voucherId){
//        Long userId = UserHolder.getUser().getId();
//
//        //1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        int r = result.intValue();
//        //2.判断结果是否为0
//        if(r != 0){
//            //3.不为0，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足": "无购买资格");
//        }
//        long orderId = redisIdWorker.nextId("order");
//        //4.为0，代表有购买资格，将下单信息保存到阻塞队列
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        //获取代理对象（事务），只有使用代理对象才能开启事务，如果使用this调用内部方法，会绕开代理对象，无法开启事务
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        orderTasks.add(voucherOrder);
//        return Result.ok(orderId);
//    }
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//
//        LocalDateTime now = LocalDateTime.now();
//
//        //1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        if(beginTime.isAfter(now)){
//            return Result.fail("优惠券秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        if(now.isAfter(endTime)){
//            return Result.fail("优惠券秒杀已经结束");
//        }
//
//        //4.判断库存是否充足
//        Integer stock = seckillVoucher.getStock();
//        if(stock < 1){
//            return Result.fail("优惠券库存不足");
//        }
//
//        //实现一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock(1L,5L,TimeUnit.SECONDS );
//        if(!isLock){
//            //获取锁失败，返回错误/重试信息
//            return Result.fail("一个人只允许下一单");
//        }
//        try {
//            //获取代理对象（事务），只有使用代理对象才能开启事务，如果使用this调用内部方法，会绕开代理对象，无法开启事务
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //7.返回订单id
//            return proxy.createOrderVoucher(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//
//    }

//    @Transactional
//    public Result createOrderVoucher(Long voucherId){
//
//            //实现一人一单
//            Long userId = UserHolder.getUser().getId();
//            //one 查询订单
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//
//            //two 判断订单是否存在
//            if(count > 0){
//                //用户已经购买过了
//                return Result.fail("该优惠券只允许下单一次哦");
//            }
//
//            //5.扣减库存
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1"). //set stock = stock - 1
//                            eq("voucher_id",voucherId).gt("stock",0) //where voucher_id = voucherId and stock > 0
//                    .update();
//            if(!success){
//                //扣减失败
//                return Result.fail("优惠券库存不足");
//            }
//
//            //6.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            //获取订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            voucherOrder.setUserId(userId);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            return Result.ok(orderId);
//
//
//    }

    @Transactional
    public void createOrderVoucher(VoucherOrder voucherOrder){
        // 消息重复投递时，历史订单也不能被重新创建。
        if (getById(voucherOrder.getId()) != null) {
            return;
        }

        // 已取消和已退款的订单不占用再次购买资格。
        Long userId = voucherOrder.getUserId();
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .notIn("status", CANCELED, REFUNDED)
                .count();

        //two 判断订单是否存在
        if(count > 0){
            //用户已经购买过了
            log.error("该优惠券只允许下单一次哦");
            return;
        }

        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1"). //set stock = stock - 1
                        eq("voucher_id",voucherOrder.getVoucherId()).gt("stock",0) //where voucher_id = voucherId and stock > 0
                .update();
        if(!success){
            //扣减失败
            log.error("优惠券库存不足");
            return;
        }

        save(voucherOrder);


    }

    @Override
    public Result normalVoucher(Long voucherId) {
        if(voucherId <= 0)
        {
            return Result.fail("优惠券ID不合法");
        }
        //1.获取下单用户
        Long userId = UserHolder.getUser().getId();
        //2.查询优惠券信息
        Voucher voucher = voucherService.getById(voucherId);
        if(voucher == null)
        {
            return Result.fail("优惠券不存在");
        }
        Integer type = voucher.getType();
        Integer status = voucher.getStatus();
        if(!Integer.valueOf(0).equals(type)){
            return Result.fail("该优惠券不是普通券");
        }
        if(status != 1) {
            return Result.fail("该优惠券已经下架或过期");
        }
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(UNPAID);
        boolean save = save(voucherOrder);
        if(!save)
        {
            return Result.fail("优惠券下单失败");
        }
        return Result.ok(String.valueOf(orderId));
    }

    @Override
    public Result queryOrders(Integer current) {
        if (current == null || current < 1) {
            return Result.fail("页码不合法");
        }
        Page<VoucherOrder> page = query()
                .eq("user_id", UserHolder.getUser().getId())
                .orderByDesc("create_time", "id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<VoucherOrder> orders = page.getRecords();
        if (!orders.isEmpty()) {
            List<Long> voucherIds = orders.stream().map(VoucherOrder::getVoucherId).distinct()
                    .collect(Collectors.toList());
            Map<Long, String> titles = voucherService.listByIds(voucherIds).stream()
                    .collect(Collectors.toMap(Voucher::getId, Voucher::getTitle));
            orders.forEach(order -> order.setVoucherTitle(titles.get(order.getVoucherId())));
        }
        return Result.ok(orders, page.getTotal());
    }

    @Override
    public Result queryOrder(Long id) {
        VoucherOrder order = query()
                .eq("id", id)
                .eq("user_id", UserHolder.getUser().getId())
                .one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher != null) {
            order.setVoucherTitle(voucher.getTitle());
        }
        return Result.ok(order);
    }

    @Override
    public Result payOrder(Long id) {
        boolean updated = orderUpdate(id, UNPAID, PAID)
                .set("pay_type", DEMO_PAY_TYPE)
                .set("pay_time", LocalDateTime.now())
                .update();
        return orderUpdateResult(updated);
    }

    @Override
    @Transactional
    public Result cancelOrder(Long id) {
        return finishOrder(id, orderUpdate(id, UNPAID, CANCELED).update());
    }

    @Override
    public Result redeemOrder(Long id) {
        boolean updated = orderUpdate(id, PAID, REDEEMED)
                .eq("pay_type", DEMO_PAY_TYPE)
                .set("use_time", LocalDateTime.now())
                .update();
        return orderUpdateResult(updated);
    }

    @Override
    @Transactional
    public Result refundOrder(Long id) {
        boolean updated = orderUpdate(id, PAID, REFUNDED)
                .eq("pay_type", DEMO_PAY_TYPE)
                .set("refund_time", LocalDateTime.now())
                .update();
        return finishOrder(id, updated);
    }

    private Result finishOrder(Long id, boolean updated) {
        if (!updated) {
            return orderUpdateResult(false);
        }
        VoucherOrder order = getById(id);
        boolean restored = seckillVoucherService.update()
                .eq("voucher_id", order.getVoucherId())
                .setSql("stock = stock + 1")
                .update();
        if (restored) {
            seckillStockReleaseService.enqueue(order);
        }
        return Result.ok();
    }

    private UpdateChainWrapper<VoucherOrder> orderUpdate(Long id, int status, int nextStatus) {
        return update()
                .eq("id", id)
                .eq("user_id", UserHolder.getUser().getId())
                .eq("status", status)
                .set("status", nextStatus);
    }

    private Result orderUpdateResult(boolean updated) {
        return updated ? Result.ok() : Result.fail("订单不存在或当前状态不允许此操作");
    }
}
