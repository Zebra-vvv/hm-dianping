package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryType() {
        // 1、从 redis 查询商铺类型缓存
        String key = CACHE_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        // 2、判断 redis 里是否存在
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 3、redis 里存在，直接返回
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 4、redis 里不存在，去查数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 5、数据库也不存在，返回错误
        if (shopTypeList == null) {
            return Result.fail("店铺类型不存在！");
        }
        // 6、数据库存在，写入 redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7、返回
        return Result.ok(shopTypeList);
    }
}
