package com.brayton.weibo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHashed;

    @Column(nullable = false, unique = true)
    private String email;

    @Setter
    private String avatarUrl;

    @Setter
    private String bio;

    private LocalDateTime createdAt =  LocalDateTime.now();

    public User() {}
    public User(String username, String passwordHashed, String email) {
        this.username = username;
        this.passwordHashed = passwordHashed;
        this.email = email;
    }
}
