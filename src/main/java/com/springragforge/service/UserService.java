package com.springragforge.service;

import com.springragforge.entity.User;
import com.springragforge.repository.UserRepository;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户服务。「登录即注册」：验证码通过后查邮箱，有就返回已有 id，没有就用雪花算法
 * 生成新 id 落一条。返回 Long，控制器转成 String 交给前端（雪花超过 JS 安全整数）。
 */
@Service
public class UserService {

    @Resource
    private UserRepository userRepository;

    @Resource
    private SnowflakeIdGen snowflakeIdGen;

    /**
     * 按邮箱查用户，不存在则注册。返回用户 id（即 userId）。
     */
    @Transactional
    public Long findOrCreateUserId(String email) {
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseGet(() -> {
                    User user = new User(snowflakeIdGen.nextId(), email);
                    userRepository.save(user);
                    return user.getId();
                });
    }
}
