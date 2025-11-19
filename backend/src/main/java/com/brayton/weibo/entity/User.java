package com.brayton.weibo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHashed;

    @Column(nullable = false, unique = true)
    private String email;

    @Setter
    private Gender gender = Gender.FEMALE;

    @Setter
    private String avatarUrl;

    @Setter
    private String bio;

    @Setter
    private LocalDate birthday;

    @Setter
    private Long phoneNumber;

    private LocalDateTime createdAt =  LocalDateTime.now();

    @Setter
    private LocalDateTime updatedAt = LocalDateTime.now();

    public User(String username, String passwordHashed, String email) {
        this.username = username;
        this.passwordHashed = passwordHashed;
        this.email = email;
    }
}
