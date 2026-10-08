package com.springragforge.controller;

import com.springragforge.common.Result;
import com.springragforge.service.CodeStore;
import com.springragforge.service.MailService;
import com.springragforge.service.UserService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * QQ 邮箱验证码登录。
 *
 * <p>链路一 POST /auth/send-code：生成 6 位验证码 → 原子存 Redis（5 分钟过期）
 * → JavaMailSender 发邮件。Redis 挂了降级本地内存。
 * <p>链路二 POST /auth/login：取验证码比对 → 成功立即删 key 防重放 →
 * 查用户，有拿 userId 没有就注册 → 返回 userId。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** QQ 邮箱格式：本地部分 + @qq.com。简单但不拦死，主要防手滑输错。 */
    private static final Pattern QQ_MAIL = Pattern.compile("^[A-Za-z0-9_.+\\-]+@qq\\.com$");

    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    @Resource
    private CodeStore codeStore;

    @Resource
    private MailService mailService;

    @Resource
    private UserService userService;

    public record SendCodeRequest(String email) {
    }

    public record LoginRequest(String email, String code) {
    }

    public record LoginResponse(String userId) {
    }

    /**
     * 发送验证码。
     * <p>顺序很关键：先原子存验证码（顺带防刷），再发邮件；邮件发送失败则回滚
     * 刚存的验证码，允许用户立刻重试，不会因邮件挂了被锁 5 分钟。
     */
    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody SendCodeRequest req) {
        String email = normalize(req);
        if (email == null) {
            return Result.fail("邮箱格式不正确，请输入 QQ 邮箱");
        }

        // 用 ThreadLocalRandom 而不是 Random：并发场景下 Random 的种子可能重复
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));

        // setIfAbsent 原子完成「是否刚发过」判定 + 存储，防刷和写入一把梭
        boolean stored = codeStore.saveCodeIfAbsent(email, code, CODE_TTL);
        if (!stored) {
            return Result.fail("验证码已发送，请 5 分钟内使用或稍后重试");
        }

        try {
            mailService.sendCode(email, code);
        } catch (MailException e) {
            log.error("验证码邮件发送失败：{}", email, e);
            codeStore.deleteCode(email); // 回滚，允许立刻重试
            return Result.fail("验证码发送失败，请稍后重试");
        }
        return Result.ok();
    }

    /**
     * 验证码登录。比对成功立即删 Redis key 防重放，再「登录即注册」签发 userId。
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest req) {
        String email = normalize(new SendCodeRequest(req.email()));
        if (email == null) {
            return Result.fail("邮箱格式不正确，请输入 QQ 邮箱");
        }
        String code = req.code();
        if (code == null || code.isBlank()) {
            return Result.fail("请输入验证码");
        }

        String stored = codeStore.getCode(email);
        if (stored == null) {
            return Result.fail("验证码已过期，请重新发送");
        }
        if (!stored.equals(code)) {
            return Result.fail("验证码错误");
        }
        // 比对成功立即删 key，防止同一个验证码被重放使用
        codeStore.deleteCode(email);

        // 雪花 id 是 Long，超过 JS 安全整数；转成字符串交给前端，避免精度丢失
        String userId = String.valueOf(userService.findOrCreateUserId(email));
        return Result.ok(new LoginResponse(userId));
    }

    /** 去空格 + 转小写 + 格式校验。不通过返回 null。 */
    private String normalize(SendCodeRequest req) {
        if (req == null || req.email() == null) {
            return null;
        }
        String email = req.email().trim().toLowerCase();
        if (!QQ_MAIL.matcher(email).matches()) {
            return null;
        }
        return email;
    }
}
