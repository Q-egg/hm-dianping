package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) throws InterruptedException {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @PostMapping("/normal/{voucherId}")
    public Result normalVoucher(@PathVariable Long voucherId)
    {
        return voucherOrderService.normalVoucher(voucherId);
    }

    @GetMapping
    public Result queryOrders(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return voucherOrderService.queryOrders(current);
    }

    @GetMapping("/{id}")
    public Result queryOrder(@PathVariable("id") Long id) {
        return voucherOrderService.queryOrder(id);
    }

    @PostMapping("/{id}/pay")
    public Result payOrder(@PathVariable("id") Long id) {
        return voucherOrderService.payOrder(id);
    }

    @PostMapping("/{id}/cancel")
    public Result cancelOrder(@PathVariable("id") Long id) {
        return voucherOrderService.cancelOrder(id);
    }

    @PostMapping("/{id}/redeem")
    public Result redeemOrder(@PathVariable("id") Long id) {
        return voucherOrderService.redeemOrder(id);
    }

    @PostMapping("/{id}/refund")
    public Result refundOrder(@PathVariable("id") Long id) {
        return voucherOrderService.refundOrder(id);
    }
}
