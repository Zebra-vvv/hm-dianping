package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 * 服务实现类
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

    @Override
    public Result queryById(Long id) {
        // 缓存空值解决缓存穿透
        Shop shop = cacheClient.
                queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        //Shop shop = cacheClient.
        //        queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);


        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);
    }

//    public Shop queryWithMutex(Long id) {
//        // 1、根据 id 从 redis 查询商铺缓存
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 2、判断 redis 里是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3、redis 里存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 经过 isNotBlank 的判断，走到这里，要么是 null，要么是空字符串
//        if (shopJson != null) {
//            // 不是null，只剩 空字符串 的可能性，直接返回，防止缓存穿透
//            return null;
//        }
//
//        // 4、实现缓存重建
//        // 4.1、获取互斥锁
//        String lockKey = "lock:shop:" + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//
//            // 4.2、判断是否获取成功
//            if (!isLock) {
//                // 4.3、失败，则休眠并重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//
//            // 4.3 成功，获取到锁之后应该再查一次缓存
//            shopJson = stringRedisTemplate.opsForValue().get(key);
//            if (StrUtil.isNotBlank(shopJson)) {
//                return JSONUtil.toBean(shopJson, Shop.class);
//            }
//            if (shopJson != null) {
//                return null;
//            }
//
//            // 4.5、根据id查询数据库
//            shop = getById(id);
//
//            // 5、数据库也不存在，返回错误
//            if (shop == null) {
//                // 先将 空字符串 写入 redis（缓存空值，防止缓存穿透）
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            // 6、数据库存在，写入 redis
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 7、释放互斥锁
//            unLock(lockKey);
//        }
//
//        // 8、返回
//        return shop;
//    }

//    public Shop queryWithPassThrough(Long id) {
//        // 1、根据 id 从 redis 查询商铺缓存
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 2、判断 redis 里是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3、redis 里存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 经过 isNotBlank 的判断，走到这里，要么是 null，要么是空字符串
//        if (shopJson != null) {
//            // 不是null，只剩 空字符串 的可能性，直接返回，防止缓存穿透
//            return null;
//        }
//
//        // 4、redis 里不存在，去查数据库
//        Shop shop = getById(id);
//
//        // 5、数据库也不存在，返回错误
//        if (shop == null) {
//            // 先将 空字符串 写入 redis（缓存空值，防止缓存穿透）
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        // 6、数据库存在，写入 redis
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        // 7、返回
//        return shop;
//    }

//    // 获取互斥锁
//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    // 释放互斥锁
//    private void unLock(String key) {
//        stringRedisTemplate.delete(key);
//    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 1、更新数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        updateById(shop);

        // 2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

//    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
//        // 1、查询店铺数据
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        // 2、封装逻辑过期时间
//        RedisData redisdata = new RedisData();
//        redisdata.setData(shop);
//        redisdata.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//
//        // 3、写入Redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisdata));
//    }
}


