package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期，用另外一个类，把逻辑过期时间也包装进去，然后一起写到redis里去
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPreFix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit
    ) {

        // 1、根据 id 从 redis 查询商铺缓存
        String key = keyPreFix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2、判断 redis 里是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3、redis 里存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 经过 isNotBlank 的判断，走到这里，要么是 null，要么是空字符串
        if (json != null) {
            // 不是null，只剩 空字符串 的可能性，直接返回，防止缓存穿透
            return null;
        }

        // 4、redis 里不存在，去查数据库
        R r = dbFallback.apply(id);

        // 5、数据库也不存在，返回错误
        if (r == null) {
            // 先将 空字符串 写入 redis（缓存空值，防止缓存穿透）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6、数据库存在，写入 redis
        this.set(key, r, time, unit);

        // 7、返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            String keyPreFix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit
    ) {
        // 1、根据 id 从 redis 查询商铺缓存
        String key = keyPreFix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2、判断 redis 里是否存在
        if (StrUtil.isBlank(json)) {
            // 3、redis 里不存在，直接返回
            return null;
        }
        // 4、redis 里存在，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5、判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1、未过期，直接返回店铺信息
            return r;
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
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入 redis
                    this.set(key, r1, time, unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 6.4、不管成没成功，都会先返回已过期商铺信息
        return r;
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

}
