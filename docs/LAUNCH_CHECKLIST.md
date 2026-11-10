# 上线前检查清单

按优先级排列：**P0** 不完成不能上线，**P1** 上线后尽快补齐，**P2** 视用户规模和合规要求安排。
每项尽量写明涉及的文件和验收方式，完成后勾选。

> 已具备、无需重复处理：JWT 密钥启动校验（拒绝弱/公开值）、Flyway 迁移 + `ddl-auto: validate`、
> AI 问答限流与当日花费熔断、点赞/通知幂等、Actuator 走独立管理端口且只绑 127.0.0.1、结构化 JSON 日志 + traceId。

---

## P0 · 安全

### 基础设施与密钥

- [x] `docker-compose.yml` 去掉明文密码，所有密钥改由根目录 `.env` 注入（模板 `.env.example`，必填项缺失时 compose 直接报错）；`.env` 已在 `.gitignore`
- [x] Redis 设置密码（`requirepass`），后端配置 `SPRING_DATA_REDIS_PASSWORD`
- [x] RabbitMQ 设置非默认账号密码（`RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS`），不用 guest
- [x] 5432 / 6379 / 5672 / 15672 只绑 `127.0.0.1`，外网不可达；本机开发仍可连。远程管理界面走 `ssh -L 15672:localhost:15672`
- [x] JWT、R2 凭据通过环境变量注入，覆盖打进 jar 的 `application.yml`
- [x] 生产容器关闭 SQL 日志（`SPRING_JPA_SHOW_SQL=false`）
- [ ] 生产 `.env` 的 `JWT_SECRET` 用 `openssl rand -base64 48` 新生成，不复用本地/历史值；各密码单独生成
- [ ] R2、Voyage、Anthropic 的生产密钥与开发密钥分开，R2 Access Key 权限收窄到单个 bucket
- [x] `application.yml`（含本地密钥）不再打进 jar / 镜像（`pom.xml` 的 maven-jar-plugin 排除）；
      非密钥的基础配置移到入库的 `application.properties`，容器内配置 = properties + 环境变量
- [x] 已有数据卷的环境改密：`POSTGRES_PASSWORD` 只在 pgdata 首次初始化时生效，旧卷需执行
      `docker exec -it postgresql_weibo psql -U postgres -c "ALTER USER postgres PASSWORD '<新密码>';"`
      RabbitMQ 没挂数据卷，重建容器即用新账号。本地环境已完成；生产若沿用旧数据卷需同样执行

### 文件上传（`common/R2Client.java`、`controller/FileController.java`）

- [ ] Content-Type 白名单：只允许 `image/jpeg`、`image/png`、`image/webp`、`image/gif`
- [ ] 读文件头魔数校验真实类型，不信任客户端传的 `contentType`
- [ ] 对象 key 改为 `UUID + 规范扩展名`，不再拼接 `getOriginalFilename()`
- [ ] 单文件大小上限（如 10MB）在业务层再校验一次
- [ ] 上传接口加限流（按 userId）
- [ ] 验收：上传 `.html` / `.svg` / 改名的非图片文件均被拒绝

### 认证与账号（`service/UserService.java`、`dto/RegisterRequest.java`）

- [x] `/login`、`/register` 加限流（按 IP + 用户名），防暴力破解
      ——`AuthRateLimitPolicies`：login-ip 30/10m、login-user 10/10m、register-ip 10/1h，超限 429；
      `server.forward-headers-strategy=native` 在 Caddy 后取真实 IP（只采信内网直连方的 X-Forwarded-For）
- [x] 登录失败统一返回「用户名或密码错误」，不区分用户不存在 / 密码错误
      ——统一 `INVALID_CREDENTIALS(1008)`；用户不存在时也跑一次 BCrypt（启动时预生成假哈希），实测两种失败耗时中位数 61ms / 58ms
- [x] 注册校验：密码最短 8 位；用户名长度与字符集限制；邮箱长度限制
      ——密码 8–72（BCrypt 72 字节上限）；用户名 2–20，Unicode 字母 / 数字 / 下划线（兼容现有中文昵称）；邮箱 ≤ 254；
      规则集中在 `dto/UserConstraints`，前端 `lib/userRules.ts` 同步预校验
- [x] `PUT /set` 改用独立的 `UpdateUserRequest` + `@Valid`：username / bio 长度限制，avatarUrl 只允许本站 R2 域名
      ——bio ≤ 160、birthday 不能是未来；`@R2AvatarUrl` 要求以 `r2.publicBaseUrl` + `/` 开头（防 `r2.dev.evil.com` 这类伪装）
- [x] 验收：超长 bio、外链头像、空密码均返回 400
      ——参数校验失败由 200 + 4001 改为真正的 HTTP 400（body 不变，前端拦截器照常解析）；本地实测三者均 400

### 接口滥用

- [x] `GET /users` 删除或改为分页，并消除逐个用户查帖子数的 N+1
      ——按 `(follower_count DESC, id DESC)` keyset 分页（`lastId` + `size`，V9 加配套索引）；发帖数每页一次 `group by` 批量查；前端 `/users` 加「Load more」
- [x] 所有分页参数（`size`、`k`）统一加上限（如 50），`controller/PostController.java` 逐个检查
      ——上限 20（`controller/PageLimits`），超出截断、非正数按 1；Post / Follow / Notification / User 四个 controller 共 11 处
- [x] `config/WebSocketConfig.java` 的 `setAllowedOriginPatterns("*")` 改为与 CORS 相同的白名单
      ——改为 `setAllowedOrigins`，读同一个 `cors.allowed-origins`
- [x] 反向代理关闭或脱敏 `/ws` 请求的 query 日志（token 在 query 参数里）
      ——Caddy 访问日志删除 query 里的 `token` 和 `Authorization` 头
- [x] 验收：`size=100000` 返回被截断的结果；跨域 origin 的 WebSocket 握手被拒
      ——实测 `/users`、`/posts`、`/user/{id}/posts` 传 `size=100000` 均返回 20 条；WebSocket 非白名单 origin 握手 403（见 network-domain PR）

---

## P0 · 部署

### 镜像

- [x] `backend/Dockerfile` 改为多阶段构建（Maven 构建阶段 + JRE 运行阶段），不依赖宿主机预先 `mvn package`
      ——`start.sh` 去掉宿主机打包步骤；`.dockerignore` 排除 `target` 和含密钥的 `application.yml`
- [x] 后端容器以非 root 用户运行，设置 `-XX:MaxRAMPercentage=75`
      ——运行用户 `app`；`JAVA_TOOL_OPTIONS` 从 compose 移入 Dockerfile
- [x] `frontend/Dockerfile` 改用 `npm ci`，多阶段构建，`next.config.ts` 开启 `output: 'standalone'`
      ——运行阶段以 `node` 用户跑 `server.js`；`.dockerignore` 排除宿主机 `node_modules` / `.next`
- [x] 镜像 tag 固定版本：`redis:7` → 具体小版本，`rabbitmq:3-management` 同理
      ——`redis:7.4.12` / `rabbitmq:3.13.7-management` / `pgvector/pgvector:0.8.6-pg15`（与原 tag 实际解析到的版本一致）

### 网络与域名

- [x] 反向代理（Nginx / Caddy）+ HTTPS 证书，前端与 API 走同一域名或明确子域名
      ——Caddy（`deploy/Caddyfile` + `docker-compose.prod.yml`），自动 Let's Encrypt；`APP_DOMAIN` / `API_DOMAIN` 两个子域名
      （后端 `/login` 等路径与前端路由同名，同域按路径分流会冲突）；frontend / backend 端口改为只绑 127.0.0.1
- [x] WebSocket 走 `wss://`，代理配置 `Upgrade` / `Connection` 头和读超时（SSE 问答同样需要关闭缓冲）
      ——Caddy 自动透传 Upgrade 且对长连接无空闲超时；SSE 路由 `flush_interval -1`，且只压缩 JSON，不压缩 event-stream
- [x] 前端构建参数 `NEXT_PUBLIC_API_BASE_URL` 改为正式域名（build 时内联，改了要重新构建）
      ——部署时在 `.env` 填 `https://<API_DOMAIN>`（`.env.example` 已说明），wss 地址由它自动推导
- [x] 后端设置 `CORS_ALLOWED_ORIGINS` 为正式前端域名
      ——compose 已透传 `CORS_ALLOWED_ORIGINS`，部署时在 `.env` 填 `https://<APP_DOMAIN>`
- [x] 响应头：HSTS、`X-Content-Type-Options: nosniff`、`X-Frame-Options`、基础 CSP
      ——在 Caddy 统一加；前端 CSP 放行 API 域名的 https/wss 与 https 图片，API 域名 `default-src 'none'`

### Compose 编排

- [x] postgresql / redis / rabbitmq 加 `healthcheck`，backend 用 `depends_on: condition: service_healthy`
      ——`pg_isready` / `redis-cli ping`（`REDISCLI_AUTH` 认证，匹配 PONG）/ `rabbitmq-diagnostics ping`
- [x] 所有服务加 `restart: unless-stopped`
- [x] 资源限制（内存上限），避免单个服务拖垮主机
      ——`deploy.resources.limits.memory`：postgres 1g / redis 512m / rabbitmq 512m / backend 1g / frontend 512m；
      backend 同时设 `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`。Redis 存 feed 且不淘汰，数据量增长后需调大

### CI

- [x] 新建 `.github/workflows`：`./mvnw test`、`npm ci && npm run lint && npm run build`
      ——`.github/workflows/ci.yml`，push main / PR 触发；顺带修掉了前端 13 个 ESLint error 让 lint 能过
- [x] Smoke test 类测试（需要真实 API key 的）在 CI 中跳过或单独触发
      ——`*SmokeTest` 已用 `@EnabledIfEnvironmentVariable` 守卫，CI 不注入 key，自动跳过

---

## P1 · 可靠性

- [ ] RabbitMQ 消费失败策略：配置重试上限 + 死信队列（DLX/DLQ），`config/RabbitConfig.java` 当前没有；
      确认 `spring.rabbitmq.listener.simple.default-requeue-rejected=false`，避免毒消息无限重投
- [ ] 死信队列堆积告警
- [ ] PostgreSQL 定时备份（`pg_dump` 或托管数据库快照），保留策略明确
- [ ] Redis AOF 文件定期备份；确认 `./start.sh backfill` 能从 PostgreSQL 重建 feed，并演练一次
- [ ] 演练一次完整恢复：新机器 → 恢复数据库 → 回灌 Redis → 服务可用
- [ ] Prometheus 抓取 `:8081/actuator/prometheus`，Grafana 面板 + 告警：5xx 比例、p99 延迟、队列堆积、AI 当日花费
- [ ] 日志集中收集（Loki / ELK 等）并设置保留期
- [ ] 若部署多个后端实例：WebSocket 从 SimpleBroker 改为 RabbitMQ STOMP relay，否则跨实例推送丢失；
      单实例上线则在部署文档注明此限制
- [ ] 数据库连接池、Redis 连接池参数按机器规格调整

---

## P2 · 体验与合规

### 登录态

- [ ] JWT 1 小时过期且无续期：增加 refresh token，或改用 httpOnly + Secure + SameSite cookie
- [ ] token 不再放 localStorage（XSS 可窃取）
- [ ] 支持服务端吊销（改密码 / 登出后旧 token 失效）

### 账号与内容

- [ ] 邮箱验证、找回密码
- [ ] 用户注销账号与数据删除
- [ ] 举报入口与内容审核流程（帖子、评论、头像、AI 回答）
- [ ] 隐私政策、用户协议页面
- [ ] 如面向中国大陆用户：ICP 备案、实名认证、内容安全审核等合规要求

### 测试

- [ ] 核心业务补测试：`PostService`、`LikeService`、`CommentService`、`FollowService`（可见性规则、权限、计数）
- [ ] 可见性规则测试：`isVisibleToUser` 与 SQL 版 `visibilityFilter` 结果一致
- [ ] 前端关键流程手工回归：注册 → 登录 → 发帖（含图片）→ 点赞 / 评论 → 收到通知 → 搜索 / 问答
- [ ] 上线前用 `docs/QUICKSTART_COMMENT_THROUGHPUT_TEST.md` 在生产规格机器上跑一次压测

---

## 上线当天

- [ ] 生产环境变量逐项核对（数据库、Redis、RabbitMQ、JWT、R2、Voyage、Anthropic、CORS、AI 预算）
- [ ] Flyway 迁移在生产库执行成功，`ddl-auto: validate` 启动通过
- [ ] `GET /ping` 正常；`:8081/actuator/health` 各组件 UP
- [ ] 外网扫描确认只开放 80/443（和 SSH）
- [ ] 按「测试」一节的关键流程走一遍
- [ ] 准备回滚方案：上一版镜像 tag + 数据库备份时间点
