package com.springragforge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 user 表。id（BIGINT）即 userId，由应用层雪花算法生成，DB 不自增。
 * nickname / status / daily_quota 用 Java 字段初值兜底，与 DDL 默认值一致；
 * created_at / updated_at 由 MySQL 的 DEFAULT / ON UPDATE CURRENT_TIMESTAMP 托管，
 * 因此 insertable=false、updatable=false，Hibernate 不参与这两个时间戳的写入。
 *
 * <p>「登录即注册」：验证码通过后若邮箱不存在就自动落一条。
 */
@Entity
@Table(name = "user")
public class User {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname = "";

    @Column(name = "status", nullable = false)
    private Integer status = 1;

    @Column(name = "daily_quota", nullable = false)
    private Integer dailyQuota = 20;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public User() {
    }

    /** 注册用：只指定 id 和 email，其余字段走默认值。 */
    public User(Long id, String email) {
        this.id = id;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getDailyQuota() {
        return dailyQuota;
    }

    public void setDailyQuota(Integer dailyQuota) {
        this.dailyQuota = dailyQuota;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
