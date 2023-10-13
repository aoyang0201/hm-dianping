package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.VoucherOrder;

public interface IVoucherOrderhandleService extends IService<VoucherOrder> {
    void handleVoucherOrder(VoucherOrder voucherOrder);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
