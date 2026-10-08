package com.springragforge.service;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送。封装 JavaMailSender，只负责发验证码这一种邮件。
 */
@Service
public class MailService {

    @Resource
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.username:}")
    private String from;

    /**
     * 发送验证码邮件。SMTP 出错会抛 MailException，由调用方决定回滚验证码。
     */
    public void sendCode(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("RagForge 登录验证码");
        message.setText("您的验证码是：" + code + "，5分钟内有效。");
        javaMailSender.send(message);
    }
}
