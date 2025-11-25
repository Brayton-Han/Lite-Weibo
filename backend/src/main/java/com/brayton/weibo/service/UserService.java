package com.brayton.weibo.service;

import com.brayton.weibo.config.security.JWTService;
import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.dto.RegisterRequest;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final JWTService jWTService;
    private final PasswordEncoder passwordEncoder;
    private final FollowRepository followRepository;

    public void register(RegisterRequest request) {

        // 1. 检查用户名或邮箱是否已存在
        if (userRepository.existsByUsernameOrEmail(request.getUsername(), request.getEmail())) {
            throw new WeiboException(CommonErrorCode.USER_EXISTS);
        }

        // 2. 处理密码
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        // 3. 保存用户
        User newUser = new User(request.getUsername(), encodedPassword, request.getEmail());
        userRepository.save(newUser);
    }

    public String login(LoginRequest request) {

        // 1. 查找用户
        Optional<User> userOptional = userRepository.findByUsername(request.getUsername());
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }
        User user = userOptional.get();

        // 2. 验证密码 (实际应用中需要用 passwordEncoder.matches(rawPassword, encodedPassword) 验证)
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHashed())) { // 临时简化处理
            throw new WeiboException(CommonErrorCode.INVALID_PASSWORD);
        }

        return jWTService.generateToken(user.getId());
    }

    public UserResponse getUserInfoById(long id, long selfId) {

        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return new UserResponse(userOptional.get(), followRepository.existsByFollowerIdAndFollowingId(selfId, id));
    }

    public String getUsernameById(long id) {

        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return userOptional.get().getUsername();
    }

    public void update(long id, UserResponse info) {
        // 1. 查找当前用户
        // 必须从数据库获取最新的实体对象，而不是直接 new User()，否则会丢失原有的其他字段（如密码、创建时间等）
        User user = userRepository.findById(id)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.USER_NOT_FOUND));

        // 2. 处理用户名变更逻辑
        // 如果前端传来的用户名和数据库里存的不一样，说明用户想改名
        if (!user.getUsername().equals(info.getUsername())) {
            // 必须检查新名字是否已经被其他人占用了
            Optional<User> existingUser = userRepository.findByUsername(info.getUsername());
            if (existingUser.isPresent()) {
                throw new WeiboException(CommonErrorCode.USERNAME_ALREADY_EXISTS);
            }
            user.setUsername(info.getUsername());
        }

        // 3. 更新其他基础信息
        user.setGender(info.getGender());
        user.setBio(info.getBio());
        user.setBirthday(info.getBirthday());

        // 4. 保存更改
        userRepository.save(user);
    }
}
