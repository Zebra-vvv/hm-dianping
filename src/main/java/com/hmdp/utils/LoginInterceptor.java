package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1、获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // token为空，直接拦截，返回401状态码（未授权）
            response.setStatus(401);
            return false;
        }
        // 2、基于 token 获取 redis 中的用户
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // 3、判断用户是否存在
        if (userMap.isEmpty()) {
            // 4、不存在（根据 token 去 redis 里没查到数据），拦截，返回401状态码（未授权）
            response.setStatus(401);
            return false;
        }
        // 5、将查询到的 Hash 数据转为 UserDTO 对象
        UserDTO userDto = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 6、存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDto);

        // 7、拦截器一旦被触发（说明用户在操作），刷新token有效期（维持登录状态）
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
