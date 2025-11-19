// src/main/java/com/brayton/weibo/dto/UserResponse.java
package com.brayton.weibo.dto;

import com.brayton.weibo.entity.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;

@Getter
@Setter
@NoArgsConstructor
public class UserResponse implements UserDetails {
    private Long id;
    private String username;
    private String email;
    private String avatarUrl;
    private String bio;
    private LocalDateTime createdAt;
    private Collection<? extends GrantedAuthority> authorities; // 存储用户的角色/权限

    // 构造函数，用于将 User 实体转换为 Response DTO
    public UserResponse(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.avatarUrl = user.getAvatarUrl();
        this.bio = user.getBio();
        this.createdAt = user.getCreatedAt();
    }

    // ---------------------- 实现 UserDetails 接口方法 ----------------------

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    @Override
    public String getPassword() {
        // 在 JWT 场景中，通常不需要密码，返回 null 即可
        return null;
    }

    @Override
    public String getUsername() {
        return this.id.toString(); // Spring Security 依赖这个字段作为唯一标识
    }

    // 通常将这些方法设置为 true，因为实际状态检查应该在 Filter 或 Service 中完成
    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return true; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public boolean isEnabled() { return true; }
}