package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1、获取session
        HttpSession session = request.getSession();

        // 2、获取session中的用户
        Object user = session.getAttribute("user");

        // 3、判断用户是否存在
        if (user == null) {
            // 4、session中没有登录信息，拦截，返回401状态码（未授权）
            response.setStatus(401);
            return false;
        }
        // 5、存在，保存用户信息到ThreadLocal

        // ✅ 强转为 UserDTO
        UserDTO userDTO = (UserDTO) user;

        // ✅ 存入 ThreadLocal
        UserHolder.saveUser(userDTO);

        // 6、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
