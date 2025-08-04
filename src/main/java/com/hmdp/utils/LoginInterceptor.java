package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1、判断是否需要拦截（Threadlocal中是否有用户信息）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 2、没有，返回401未授权状态码
            response.setStatus(401);

            // 3、并拦截
            return false;
        }

        // 2、有，放行
        return true;
    }
}
