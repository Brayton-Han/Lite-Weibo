package com.brayton.weibo.service;

import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.dto.LoginResponse;
import com.brayton.weibo.dto.RegisterRequest;
import com.brayton.weibo.dto.UpdateUserRequest;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final JWTService jWTService;
    private final PasswordEncoder passwordEncoder;
    private final FollowRepository followRepository;
    private final PostRepository postRepository;

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

    public LoginResponse login(LoginRequest request) {

        // 用户不存在和密码错误返回同一个错误码；不存在时也跑一次 BCrypt，
        // 否则「不存在」立刻返回、「密码错」要等几十毫秒，响应时间就能区分出用户名是否存在
        Optional<User> userOptional = userRepository.findByUsername(request.getUsername());
        String hash = userOptional.map(User::getPasswordHashed).orElseGet(this::dummyHash);
        boolean matches = passwordEncoder.matches(request.getPassword(), hash);
        if (userOptional.isEmpty() || !matches) {
            throw new WeiboException(CommonErrorCode.INVALID_CREDENTIALS);
        }
        User user = userOptional.get();

        return new LoginResponse(jWTService.generateToken(user.getId()), user.getId().toString());
    }

    /** 用户不存在时拿来比对的哈希，与真实哈希同一算法同一强度 */
    private volatile String dummyHash;

    /** 启动时预先生成：否则第一次「用户不存在」要多算一次 encode，耗时能区分出来 */
    @PostConstruct
    void warmUpDummyHash() {
        dummyHash();
    }

    private String dummyHash() {
        String h = dummyHash;
        if (h == null) {
            h = passwordEncoder.encode("dummy-password-for-timing");
            dummyHash = h;
        }
        return h;
    }

    public UserResponse getUserInfoById(long id, long selfId) {

        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return new UserResponse(userOptional.get(),
                followRepository.existsByFollowerIdAndFollowingId(selfId, id),
                followRepository.existsByFollowerIdAndFollowingId(id, selfId),
                followRepository.findFriendCountIds(id),
                postRepository.countPostsByUserId(id));
    }
    /**
     * 列表场景的用户卡片（不含关注关系与计数）：一次 findAllById 取回，按 ids 原顺序返回。
     * 期间被删除的用户直接跳过，不因一个人整页失败。
     */
    public List<UserResponse> getUserInfosInOrder(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, User> byId = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            byId.put(u.getId(), u);
        }
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(u -> new UserResponse(u, false, false, 0, 0))
                .toList();
    }

    public String getUsernameById(long id) {

        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return userOptional.get().getUsername();
    }

    public void update(long id, UpdateUserRequest info) {
        // 1. 查找当前用户
        // 必须从数据库获取最新的实体对象，而不是直接 new User()，否则会丢失原有的其他字段（如密码、创建时间等）
        User user = userRepository.findById(id)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.USER_NOT_FOUND));

        // 2. 处理用户名变更逻辑
        // 如果前端传来的用户名和数据库里存的不一样，说明用户想改名
        if (info.getUsername() != null && !user.getUsername().equals(info.getUsername())) {
            // 必须检查新名字是否已经被其他人占用了
            Optional<User> existingUser = userRepository.findByUsername(info.getUsername());
            if (existingUser.isPresent()) {
                throw new WeiboException(CommonErrorCode.USERNAME_ALREADY_EXISTS);
            }
            user.setUsername(info.getUsername());
        }

        // 3. 更新其他基础信息
        if (info.getAvatarUrl() != null)
            user.setAvatarUrl(info.getAvatarUrl());
        if (info.getGender() != null)
            user.setGender(info.getGender());
        if (info.getBio() != null)
            user.setBio(info.getBio());
        if (info.getBirthday() != null)
            user.setBirthday(info.getBirthday());

        // 4. 保存更改
        userRepository.save(user);
    }

    /**
     * 用户列表，按粉丝数降序 keyset 分页。lastId 为上一页最后一个用户的 id，null 表示第一页。
     * 游标位置取该用户当前的粉丝数；翻页间隙粉丝数变化可能导致个别用户重复或跳过，列表页可接受。
     * 发帖数一次批量 group by 查出，避免逐个用户查询（N+1）。
     */
    public List<UserResponse> getUsers(Long lastId, int size) {

        PageRequest page = PageRequest.of(0, size);
        List<User> users;
        if (lastId == null) {
            users = userRepository.findUsersPage(page);
        } else {
            User cursor = userRepository.findById(lastId)
                    .orElseThrow(() -> new WeiboException(CommonErrorCode.USER_NOT_FOUND));
            users = userRepository.findUsersPageAfter(cursor.getFollowerCount(), cursor.getId(), page);
        }
        if (users.isEmpty()) return List.of();

        Map<Long, Long> postCounts = new HashMap<>();
        for (Object[] row : postRepository.countPostsByUserIds(users.stream().map(User::getId).toList())) {
            postCounts.put((Long) row[0], (Long) row[1]);
        }
        return users.stream()
                .map(u -> new UserResponse(u, false, false, 0,
                        postCounts.getOrDefault(u.getId(), 0L).intValue()))
                .toList();
    }
}
