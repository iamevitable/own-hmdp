package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 */
/*
 * 主要是
 * 读取的lua脚本
 * 取消息队列的线程
 *
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    private IVoucherOrderService proxy;//定义代理对象，提前定义后面会用到
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //注入脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @Override
    public Result seckillVoucher(Long voucherId) { //使用lua脚本
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), //这里是key数组，没有key，就传的一个空集合
                voucherId.toString(), userId.toString()
        );
        //2.判断结果是0
        int r = result.intValue();//Long型转为int型，便于下面比较
        if (r != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "优惠券已售罄" : "不能重复购买");
            }
            //2.2 为0，有购买资格，把下单信息保存到阻塞队列中
            //7.创建订单   向订单表新增一条数据，除默认字段，其他字段的值需要set
            VoucherOrder voucherOrder = new VoucherOrder();
            //7.1订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //7.2用户id
            voucherOrder.setUserId(userId);
            //7.3代金券id
            voucherOrder.setVoucherId(voucherId);
            //放入阻塞对列中
            orderTasks.add(voucherOrder);
            //获取代理对象
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            //3.返回订单id
            return Result.ok(orderId);
        }
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //利用spring提供的注解，在类初始化完毕后立即执行线程任务
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //创建线程任务，内部类方式
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            //1.获取队列中的订单信息
            try {
                VoucherOrder voucherOrder = orderTasks.take();
                //2.创建订单，这是调之前那个创建订单的方法，需要稍作改动
                handleVoucherOrder(voucherOrder);
            } catch (Exception e) {
                log.info("异常信息:",e);
            }
        }
    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(1200);
        //判断是否获取锁成功
        if (!isLock){
            log.error("您已购买过该商品，不能重复购买");
        }
        try {
            proxy.createVoucherOrder(voucherOrder);//使用代理对象，最后用于提交事务
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();//释放锁
        }
    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long voucherId = voucherOrder.getVoucherId();
        //5.一人一单
        Long userId = voucherOrder.getId();
        //5.1查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2判断是否存在
        if (count > 0){
            log.error("您已经购买过了");
        }
        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")//set stock = stock -1
                .eq("voucher_id",voucherId).gt("stock",0) //where id = ? and stock > 0
                .update();
        if (!success){
            log.error("库存不足！");
        }
        this.save(voucherOrder);
    }
}

