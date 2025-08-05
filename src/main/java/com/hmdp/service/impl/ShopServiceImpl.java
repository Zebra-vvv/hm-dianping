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

    @Override
    public Result queryById(Long id) {
        // 缓存空值解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);


        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        // 1、根据 id 从 redis 查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2、判断 redis 里是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3、redis 里不存在，直接返回
            return null;
        }
        // 4、redis 里存在，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5、判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1、未过期，直接返回店铺信息
            return shop;
        }
        // 5.2、已过期，需要缓存重建
        // 6、缓存重建
        // 6.1、获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2、判断是否获取锁成功
        if (isLock) {
            // 6.3、成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.execute(() -> {
                try {
                    this.saveShop2Redis(id, 10L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 6.4、不管成没成功，都会先返回已过期商铺信息
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        // 1、根据 id 从 redis 查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2、判断 redis 里是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3、redis 里存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 经过 isNotBlank 的判断，走到这里，要么是 null，要么是空字符串
        if (shopJson != null) {
            // 不是null，只剩 空字符串 的可能性，直接返回，防止缓存穿透
            return null;
        }

        // 4、实现缓存重建
        // 4.1、获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            // 4.2、判断是否获取成功
            if (!isLock) {
                // 4.3、失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 4.3 成功，获取到锁之后应该再查一次缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                return null;
            }

            // 4.5、根据id查询数据库
            shop = getById(id);

            // 5、数据库也不存在，返回错误
            if (shop == null) {
                // 先将 空字符串 写入 redis（缓存空值，防止缓存穿透）
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6、数据库存在，写入 redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7、释放互斥锁
            unLock(lockKey);
        }

        // 8、返回
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        // 1、根据 id 从 redis 查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2、判断 redis 里是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3、redis 里存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 经过 isNotBlank 的判断，走到这里，要么是 null，要么是空字符串
        if (shopJson != null) {
            // 不是null，只剩 空字符串 的可能性，直接返回，防止缓存穿透
            return null;
        }

        // 4、redis 里不存在，去查数据库
        Shop shop = getById(id);

        // 5、数据库也不存在，返回错误
        if (shop == null) {
            // 先将 空字符串 写入 redis（缓存空值，防止缓存穿透）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6、数据库存在，写入 redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7、返回
        return shop;
    }

    // 获取互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放互斥锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

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

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1、查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 2、封装逻辑过期时间
        RedisData redisdata = new RedisData();
        redisdata.setData(shop);
        redisdata.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3、写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisdata));
    }
}


