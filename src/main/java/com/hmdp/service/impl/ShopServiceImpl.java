package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
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
    @Override
    public Result queryById(Long id) {
//        互斥锁解决缓存击穿问题
//        Shop shop = queryWithMutex( id );
        //逻辑过期解决缓存穿透问题
        Shop shop = queryWithLogicalExpire(id);
        if ( shop == null){
            return Result.fail("shop not found");
        }
        return Result.ok(shop);
    }
    //互斥锁查询缓存
    public Shop queryWithMutex(Long id){
        String lockKey = LOCK_SHOP_KEY + id;
        //1.先从Redis中查询对应的店铺缓存信息，这里的常量值是固定的店铺前缀+查询店铺的Id
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.如果在Redis中查询到了店铺信息,并且店铺的信息不是空字符串则转为Shop类型直接返回,""和null以及"/t/n(换行)"都会判定为空即返回false
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //3.如果命中的是空字符串即我们缓存的空数据返回null
        if (shopJson != null) {
            return null;
        }
        // 4.没有命中则尝试根据锁的Id(锁前缀+店铺Id)获取互斥锁(本质是插入key),实现缓存重建
        // 调用Thread的sleep方法会抛出异常,可以使用try/catch/finally把获取锁和释放锁的过程包裹起来
        Shop shop = null;
        try {
            // 4.1 获取互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            // 4.2 判断是否获取锁成功(插入key是否成功)
            if(!isLock){
                //4.3 获取锁失败(插入key失败),则休眠一段时间重新查询商铺缓存(递归)
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // todo: 再次检测Redis中缓存的信息是否存在,如果存在则无需重建缓存
            // ........................

            //4.4 获取锁成功(插入key成功),则根据店铺的Id查询数据库
            shop = getById(id);
            // 由于本地查询数据库较快,这里可以模拟重建延时触发并发冲突
            Thread.sleep(200);
            // 5.在数据库中查不到对应的店铺则将空字符串写入Redis同时设置有效期
            if(shop == null){
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.在数据库中查到了店铺信息即shop不为null,将shop对象转化为json字符串写入redis并设置TTL
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            //7.不管前面是否会有异常，最终都必须释放锁
            unlock(lockKey);
        }
        // 最终把查询到的商户信息返回给前端
        return shop;
    }
    //逻辑过期查询缓存
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在为空
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //命中 将json转化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期 直接返回店铺信息
            return shop;
        }
        //已过期 需要缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //获取锁成功 开启独立线程 进行缓存重建
        if(isLock){
            //TODO: 缓存重建逻辑
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //获取锁失败 返回过期的店铺信息信息
        return shop;
    }
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //缓存穿透查询缓存
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询缓存
        //redis中存储的json数据，有3种可能，存在不为空，存在但为""，不存在为null
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在不为空
        if(StrUtil.isNotBlank(shopJson)){
            //2.如果缓存中有数据，则直接返回缓存数据
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //存在但为“”
        if(shopJson != null){
            return null;
        }
        //3.如果缓存中没有数据，则查询数据库
        Shop shop = getById(id);
        //4不存在，则返回错误
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5.存在，则将数据缓存到redis中 并设置缓存时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    public void saveShop2Redis(Long id,Long expireTime) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("id is null");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
