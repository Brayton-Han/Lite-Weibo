# Lite-Weibo Entity Schema 设计

本文档详细说明系统中 6 个核心 Entity 的数据库 Schema 设计。

---

## 1. User（用户表）

### Schema 定义

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `id` | BIGINT | PK, Auto-Increment | 用户唯一标识 |
| `username` | VARCHAR(255) | UNIQUE, NOT NULL | 用户名，注册时唯一 |
| `password_hashed` | VARCHAR(255) | NOT NULL | 密码哈希（bcrypt） |
| `email` | VARCHAR(255) | UNIQUE, NOT NULL | 邮箱，登录凭证 |
| `gender` | ENUM | default='FEMALE' | 性别（MALE/FEMALE/OTHER） |
| `avatar_url` | VARCHAR(2048) | nullable | S3/R2 头像 URL |
| `bio` | TEXT | nullable | 个人简介 |
| `birthday` | DATE | nullable | 生日 |
| `phone_number` | BIGINT | nullable | 电话号码 |
| `follower_count` | INT | NOT NULL, default=0 | 粉丝数（缓存字段） |
| `follow_count` | INT | NOT NULL, default=0 | 关注数（缓存字段） |
| `created_at` | TIMESTAMP | default=now() | 账户创建时间 |
| `updated_at` | TIMESTAMP | default=now() | 最后修改时间 |

### 设计考虑

- **Username & Email**: 双重唯一约束，支持用户名或邮箱登录
- **Password Hashing**: 存储 bcrypt 哈希，永不存明文
- **Follower/Follow Count**: 冗余缓存字段，避免每次查询都聚合 `follows` 表
  - 当关注/取消关注时同步更新
  - 支持快速查询用户统计信息
- **Avatar URL**: 存储 S3/Cloudflare R2 完整 URL，而非文件路径

### 索引

```sql
PRIMARY KEY (id)
UNIQUE (username)
UNIQUE (email)
```

---

## 2. Post（帖子表）

### Schema 定义

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `id` | BIGINT | PK | 帖子唯一标识 |
| `user_id` | BIGINT | FK → users | 帖子作者 |
| `type` | ENUM | default='ORIGINAL' | 帖子类型（ORIGINAL/REPOST/COMMENT） |
| `content` | TEXT | NOT NULL | 帖子内容，支持富文本/HTML |
| `images` | JSON/TEXT | nullable | 图片 URL 数组（JSON 格式） |
| `ref_post_id` | BIGINT | FK → posts | 转发/引用的原始帖子 ID |
| `visibility` | ENUM | NOT NULL, default='PUBLIC' | 可见性（PUBLIC/FOLLOWERS/FRIENDS/PRIVATE） |
| `like_count` | BIGINT | NOT NULL, default=0 | 点赞数（缓存） |
| `comment_count` | BIGINT | NOT NULL, default=0 | 评论数（缓存） |
| `repost_count` | BIGINT | NOT NULL, default=0 | 转发数（缓存） |
| `status` | ENUM | default='NORMAL' | 审核状态（NORMAL/REVIEWING/BLOCKED） |
| `deleted` | BOOLEAN | default=false | 软删除标记 |
| `is_edited` | BOOLEAN | default=false | 是否被编辑过 |
| `created_at` | TIMESTAMP | auto | 发布时间 |
| `updated_at` | TIMESTAMP | auto | 修改时间 |

### 设计考虑

#### 1. 软删除 (Soft Delete)
```java
@SQLDelete(sql = "UPDATE posts SET deleted = true WHERE id = ?")
@Where(clause = "deleted = false")
```
- 所有查询自动过滤 `deleted = false` 的记录
- 支持数据恢复和审计日志
- 删除操作不真正移除数据

#### 2. 可见性控制 (PostVisibility)
```
PUBLIC    → 所有人可见
FOLLOWERS → 粉丝可见
PRIVATE   → 仅自己可见
FRIENDS   → 互关用户可见
```
- 在查询时动态检查用户权限
- 不在 SQL WHERE 中硬编码，因为涉及关系表查询

#### 3. 缓存字段 (like_count, comment_count, repost_count)
- 冗余存储计数器，避免每次查询都 COUNT 子查询
- 在点赞/评论/转发时异步更新
- 可接受短期不一致（最终一致性）

#### 4. 图片存储 (images)
```java
@Convert(converter = ImagesConverter.class)
private List<String> images;
```
- JSON 数组格式存储多个 S3 URL
- 使用 Hibernate `@Convert` 自动序列化/反序列化
- 不直接存文件，仅存 URL

#### 5. 引用帖子 (ref_post_id)
- 支持转发功能：Repost 的 `ref_post_id` 指向原帖
- 支持评论帖子：Comment 帖子的 `ref_post_id` 指向被评论的帖子

### 索引

```sql
PRIMARY KEY (id)
FOREIGN KEY (user_id) → users(id)
FOREIGN KEY (ref_post_id) → posts(id)

-- 时间线查询优化
INDEX idx_posts_created_id (created_at, id)
INDEX idx_posts_user_created (user_id, created_at)

-- 查询最近的帖子
SELECT * FROM posts 
WHERE created_at < ? AND deleted = false 
ORDER BY created_at DESC LIMIT 10
```

---

## 3. Comment（评论表）

### Schema 定义

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `id` | BIGINT | PK | 评论唯一标识 |
| `post_id` | BIGINT | FK → posts | 所属帖子 |
| `user_id` | BIGINT | FK → users | 评论人 |
| `content` | TEXT | NOT NULL | 评论内容 |
| `created_at` | TIMESTAMP | auto | 发布时间 |

### 设计考虑

- **简洁设计**: 仅存储必要字段
- **No Edit Support**: 不支持编辑评论，只能删除后重新发
- **No Nested Comments**: 不支持链式回复（一级评论），简化设计
- **级联删除**: 当帖子被删除时，所有评论自动删除（通过 FK 级联）

### 索引

```sql
PRIMARY KEY (id)
FOREIGN KEY (post_id) → posts(id) ON DELETE CASCADE
FOREIGN KEY (user_id) → users(id)

-- 按帖子查询所有评论
INDEX (post_id, created_at)
```

---

## 4. Like（点赞表）

### Schema 定义

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `id` | BIGINT | PK | 点赞唯一标识 |
| `user_id` | BIGINT | NOT NULL | 点赞用户 |
| `post_id` | BIGINT | NOT NULL | 被赞帖子 |
| `created_at` | TIMESTAMP | auto | 点赞时间 |

### 约束

```sql
UNIQUE (user_id, post_id)  -- 同一用户只能赞一次同一帖子
```

### 设计考虑

- **复合唯一键 (user_id, post_id)**
  - 保证一个用户只能对一个帖子点赞一次
  - 重复点赞时检查约束失败，触发 UNIQUE 异常
  - 应用层捕获异常，提示"已点赞"

- **No FK 关系**
  - `user_id` 和 `post_id` 仅存储 ID，不使用 FK
  - 优点：删除帖子/用户时不级联删除，保留点赞记录便于数据分析
  - 缺点：需要在应用层验证 user_id 和 post_id 的有效性

- **Created At**
  - 用于排序点赞时间轴（可选功能）
  - 用于统计"周热点"等时间窗口功能

### 索引

```sql
PRIMARY KEY (id)
UNIQUE (user_id, post_id)  -- 复合唯一索引

-- 快速查询用户的所有点赞
INDEX (user_id, created_at DESC)

-- 快速查询帖子的所有点赞用户
INDEX (post_id, created_at DESC)
```

---

## 5. FollowRelation（关注表）

### Schema 定义

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `id` | BIGINT | PK | 关系唯一标识 |
| `follower_id` | BIGINT | NOT NULL | 关注者 |
| `following_id` | BIGINT | NOT NULL | 被关注者 |
| `created_at` | TIMESTAMP | auto | 关注时间 |

### 约束

```sql
UNIQUE (follower_id, following_id)  -- 同一关注关系只能存一次
INDEX (following_id, follower_id)   -- 反向查询索引
```

### 设计考虑

- **命名规范**:
  - `follower_id` = 主动关注的用户
  - `following_id` = 被关注的用户
  - 例如：Alice 关注 Bob → (follower_id=Alice, following_id=Bob)

- **无向关系**:
  - 当前系统采用"单向关注"（Twitter 模式）
  - 如需"双向好友"（微信模式），需额外字段或冗余存储

- **No FK**:
  - 同样不使用 FK，便于数据恢复和历史分析
  - 删除用户时保留关注记录

- **复合唯一键 (follower_id, following_id)**
  - 防止重复关注

### 索引

```sql
PRIMARY KEY (id)
UNIQUE (follower_id, following_id)

-- 查询某用户的粉丝列表
INDEX (following_id, created_at DESC)

-- 查询某用户的关注列表
INDEX (follower_id, created_at DESC)
```

### 查询示例

```sql
-- 查询 Alice 的粉丝
SELECT follower_id FROM follows 
WHERE following_id = ? ORDER BY created_at DESC;

-- 查询 Alice 的关注列表
SELECT following_id FROM follows 
WHERE follower_id = ? ORDER BY created_at DESC;

-- 检查 Alice 是否关注 Bob
SELECT 1 FROM follows 
WHERE follower_id = ? AND following_id = ? LIMIT 1;

-- 检查 Alice 和 Bob 是否互关
SELECT follower_id FROM follows 
WHERE follower_id = ? AND following_id IN (
  SELECT following_id FROM follows WHERE follower_id = ?
);
```

---

## 6. Notification（通知表）

### Schema 定义

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `id` | BIGINT | PK | 通知唯一标识 |
| `actor_id` | BIGINT | NOT NULL | 触发者 ID（谁点赞/评论/关注） |
| `target_id` | BIGINT | NOT NULL | 接收者 ID（被通知的用户） |
| `type` | ENUM | NOT NULL | 通知类型（FOLLOW/LIKE/COMMENT） |
| `post_id` | BIGINT | nullable | 相关帖子 ID（LIKE/COMMENT 需要） |
| `content` | TEXT | nullable | 评论内容（COMMENT 类型存储） |
| `read` | BOOLEAN | default=false | 已读标记 |
| `created_at` | TIMESTAMP | auto | 通知创建时间 |

### 通知类型 (EventType)

```java
enum EventType {
    FOLLOW,     // 有人关注了你
    LIKE,       // 有人赞了你的帖子
    COMMENT     // 有人评论了你的帖子
}
```

### 数据流

```
User Action (Like/Comment/Follow)
  ↓
Service 发布事件 (LikeEvent/CommentEvent/FollowEvent)
  ↓
NotificationListener 监听事件
  ↓
创建 Notification 记录
  ↓
保存到 PostgreSQL
  ↓
通过 WebSocket 实时推送给 target_id 用户
  ↓
用户标记为已读或自动删除
```

### 设计考虑

- **EventType 决定字段使用**:
  ```
  FOLLOW:  actor_id, target_id, type, created_at
  LIKE:    actor_id, target_id, type, post_id, created_at
  COMMENT: actor_id, target_id, type, post_id, content, created_at
  ```

- **Read 标记**:
  - 用户点击通知列表时标记为 read=true
  - 前端可实时更新通知计数

- **数据保留**:
  - 通知应设置 TTL（Time To Live），例如 30 天后自动删除
  - 或定期归档到历史表

- **批量聚合**（可选优化）:
  - 如果 Alice 在短时间内被 1000 人赞，不需要 1000 条通知
  - 可聚合为一条通知："{1000 people} liked your post"
  - 需要额外的 `count` 字段和逻辑

### 索引

```sql
PRIMARY KEY (id)
FOREIGN KEY (actor_id) → users(id)
FOREIGN KEY (target_id) → users(id)
FOREIGN KEY (post_id) → posts(id)

-- 查询某用户的所有通知
INDEX (target_id, created_at DESC)

-- 按类型查询未读通知
INDEX (target_id, type, read, created_at DESC)
```

---

## 完整 ER 图

```
                    ┌──────────────┐
                    │    User      │
                    ├──────────────┤
                    │ id (PK)      │
                    │ username     │
                    │ email        │
                    │ bio          │
                    │ avatar_url   │
                    │ follower_cnt │
                    │ follow_cnt   │
                    └──────────────┘
                          ▲
            ┌─────────────┼─────────────┐
            │             │             │
       (1:N)         (1:N)         (1:N)
            │             │             │
            ▼             ▼             ▼
      ┌──────────┐  ┌──────────┐  ┌─────────────┐
      │   Post   │  │ Comment  │  │   Follow    │
      ├──────────┤  ├──────────┤  ├─────────────┤
      │ id (PK)  │  │ id (PK)  │  │ id (PK)     │
      │ user_id  │  │ post_id  │  │ follower_id │
      │ content  │  │ user_id  │  │ following_id│
      │ images   │  │ content  │  └─────────────┘
      │ like_cnt │  └──────────┘
      │ com_cnt  │
      └──────────┘
            ▲
            │ (1:N)
            ▼
      ┌──────────┐
      │  Like    │
      ├──────────┤
      │ id (PK)  │
      │ user_id  │
      │ post_id  │
      └──────────┘

      ┌─────────────────┐
      │ Notification    │
      ├─────────────────┤
      │ id (PK)         │
      │ actor_id ──┐    │
      │ target_id ─┼──→ User
      │ type       │    │
      │ post_id ──┐    │
      │ read       ├──→ Post
      └─────────────────┘
```

---

## 数据库统计优化

### 缓存字段策略

为了避免每次都聚合计算，采用冗余缓存：

| 表 | 缓存字段 | 更新时机 |
|----|----------|---------|
| User | follower_count, follow_count | 关注/取消关注时 |
| Post | like_count, comment_count, repost_count | 增删点赞/评论/转发时 |

**更新示例**:
```sql
-- 用户点赞帖子
INSERT INTO likes (user_id, post_id) VALUES (?, ?);
UPDATE posts SET like_count = like_count + 1 WHERE id = ?;
```

### 索引总结

| 表 | 索引 | 用途 |
|----|------|------|
| users | PK(id), UNIQUE(username), UNIQUE(email) | 身份认证、用户查询 |
| posts | PK(id), (user_id, created_at), (created_at, id) | 时间线、用户帖子列表 |
| comments | PK(id), (post_id, created_at) | 帖子评论列表 |
| likes | PK(id), UNIQUE(user_id, post_id), (user_id, created_at) | 点赞检查、用户点赞列表 |
| follows | PK(id), UNIQUE(follower_id, following_id), (following_id, created_at) | 关注管理 |
| notifications | PK(id), (target_id, created_at), (target_id, type, read) | 通知查询 |

---

## SQL 建表语句汇总

```sql
-- 1. 用户表
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hashed VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    gender ENUM('MALE', 'FEMALE', 'OTHER') DEFAULT 'FEMALE',
    avatar_url VARCHAR(2048),
    bio TEXT,
    birthday DATE,
    phone_number BIGINT,
    follower_count INT NOT NULL DEFAULT 0,
    follow_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 2. 帖子表
CREATE TABLE posts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type ENUM('ORIGINAL', 'REPOST', 'COMMENT') DEFAULT 'ORIGINAL',
    content TEXT NOT NULL,
    images JSON,
    ref_post_id BIGINT,
    visibility ENUM('PUBLIC', 'FOLLOWERS', 'FRIENDS', 'PRIVATE') DEFAULT 'PUBLIC' NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    repost_count BIGINT NOT NULL DEFAULT 0,
    status ENUM('NORMAL', 'REVIEWING', 'BLOCKED') DEFAULT 'NORMAL',
    deleted BOOLEAN DEFAULT FALSE,
    is_edited BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (ref_post_id) REFERENCES posts(id),
    INDEX idx_posts_created_id (created_at, id),
    INDEX idx_posts_user_created (user_id, created_at)
);

-- 3. 评论表
CREATE TABLE comments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX (post_id, created_at)
);

-- 4. 点赞表
CREATE TABLE likes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, post_id),
    INDEX (user_id, created_at DESC),
    INDEX (post_id, created_at DESC)
);

-- 5. 关注表
CREATE TABLE follows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (follower_id, following_id),
    INDEX (following_id, created_at DESC),
    INDEX (follower_id, created_at DESC)
);

-- 6. 通知表
CREATE TABLE notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    type ENUM('FOLLOW', 'LIKE', 'COMMENT') NOT NULL,
    post_id BIGINT,
    content TEXT,
    read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (actor_id) REFERENCES users(id),
    FOREIGN KEY (target_id) REFERENCES users(id),
    FOREIGN KEY (post_id) REFERENCES posts(id),
    INDEX (target_id, created_at DESC),
    INDEX (target_id, type, read, created_at DESC)
);
```

---

## 关键设计决策总结

| 决策 | 理由 |
|------|------|
| **软删除** (Post) | 支持数据恢复、审计、逻辑删除 |
| **缓存计数器** | 避免 COUNT 聚合，加快页面加载 |
| **复合唯一键** (Like/FollowRelation) | 防止重复，简化业务逻辑 |
| **No FK on Like** | 保留历史数据，便于数据分析 |
| **JSON 图片数组** | 灵活存储多张图片，无需额外表 |
| **Enum 类型** | 类型安全，减少字符串对比开销 |
| **时间戳索引** | 优化时间线查询 (created_at DESC) |
| **异步计数更新** | 点赞/评论/关注时异步更新计数，提高吞吐量 |

---

## 性能优化建议

### 1. 表分片 (Sharding)
```
当数据量超过 1 亿时：
- posts → posts_0, posts_1, ... (按 user_id % N)
- follows → follows_0, follows_1, ... (按 follower_id % N)
```

### 2. 读写分离
```
- 主库：处理写操作 (INSERT/UPDATE/DELETE)
- 从库：处理读操作 (SELECT)
- 延迟：毫秒级，对用户透明
```

### 3. 消息队列处理异步任务
```
- 点赞后：发送 LIKE_EVENT → RabbitMQ → 异步更新 Post.like_count
- 关注后：发送 FOLLOW_EVENT → RabbitMQ → 异步更新 User.follower_count
```

### 4. 缓存层 (Redis)
```
- feed:{userId} → Sorted Set (帖子时间线)
- liked:{userId} → Sorted Set (用户点赞列表)
- following:{userId} → Set (用户关注列表)
```

---

## 数据库初始化检查清单

- [ ] 所有表都有 PRIMARY KEY
- [ ] 所有 FK 都指向正确的表
- [ ] UNIQUE 约束都配对应的索引
- [ ] 时间线查询有 (created_at, id) 或 (user_id, created_at) 索引
- [ ] 关键字段都有 NOT NULL 约束（除非允许 null）
- [ ] 缓存字段有默认值（like_count=0 等）
- [ ] 软删除表有 WHERE clause 过滤
- [ ] created_at/updated_at 没有手动设置（使用数据库默认值）
