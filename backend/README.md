# Lite-Weibo Backend

A **Twitter-like social media platform** built with **Java Spring Boot 3.5.7**, running on **Java 21**. Designed to be scalable with event-driven architecture and real-time capabilities.

---

## System Architecture

### **1. Data Layer (PostgreSQL + JPA)**

**Entities** - Seven core domain models:
- **User**: Profiles with followers/following counts, avatar, bio, gender, birthday
- **Post**: The main content unit with support for multiple types (original, repost, etc.)
- **Comment**: Replies to posts
- **Like**: Post/comment interactions
- **FollowRelation**: User relationship management
- **Notification**: Activity alerts for follows, likes, comments
- **PostType**, **PostVisibility**, **PostStatus**, **Gender** - Enums for structured data

**Key Features**:
- Soft deletes on posts (via Hibernate's `@SQLDelete`)
- JSON columns for image lists (using custom `ImagesConverter`)
- Lazy-loaded relationships to prevent N+1 query problems
- PostgreSQL at `localhost:5432` with auto schema updates via Hibernate

---

### **2. Service Layer (Business Logic)**

Nine specialized services handle core functionality:
- **PostService**: CRUD, visibility rules, building feed responses
- **UserService**: Authentication, registration, profile management
- **CommentService**: Comment operations with notifications
- **LikeService**: Like/unlike with async event publishing
- **FollowService**: Follow/unfollow with relationship counts
- **NotificationService**: Notification persistence and retrieval
- **FileService**: File upload handling
- **JWTService**: Token generation and validation
- **RedisService**: Caching layer

The services use **event-driven patterns** with `ApplicationEventPublisher` for asynchronous operations (e.g., likes trigger events).

---

### **3. API Layer (REST Controllers)**

Eight controllers expose endpoints:
- **UserController**: `/login`, `/register`, profile endpoints
- **PostController**: Create, read, update, delete posts
- **CommentController**: Comment operations
- **LikeController**: Toggle likes
- **FollowController**: Follow/unfollow
- **NotificationController**: Fetch notifications
- **FileController**: File uploads (20MB max)
- **GlobalExceptionHandler**: Centralized error handling

**Authentication**: Stateless JWT-based (Strategy: `SessionCreationPolicy.STATELESS`)

---

### **4. Messaging & Real-Time (RabbitMQ + WebSocket)**

**RabbitMQ** - Topic exchange pattern with 3 queues:
- `notification.follow.queue` → routes follow events
- `notification.like.queue` → routes like events
- `notification.comment.queue` → routes comment events

All bind to `notification.exchange` with routing keys like `notification.follow`, `notification.like`, etc.

**WebSocket** (STOMP over WebSocket):
- **Endpoint**: `/ws` (JWT-authenticated)
- **Message Broker**: `/topic/**` for broadcasts, `/queue/**` for user-specific messages
- **Application prefix**: `/app/**` for client→server messages
- Used for real-time push updates via `WebSocketPusher`

---

### **5. Caching (Redis)**
- **Host**: `localhost:6379`
- **Pool**: 8 max active connections
- **Timeout**: 3 seconds (3000ms)
- Used for caching hot data and reducing database load

---

### **6. Microservice Features**

**Cloud Storage (Cloudflare R2 - S3-compatible)**:
- Stores post images and user avatars
- Account ID, access keys configured in `application.yml`
- Public base URL: `https://pub-4ef9f81c6a264c37ae3775f783adb4a2.r2.dev`

**Docker Support**: Included for containerization

---

## Architecture Diagram

```
┌─────────────────────────────────────┐
│         REST API Clients            │
│     (Web/Mobile Frontend)           │
└────────────────┬────────────────────┘
                 │
      ┌──────────┴──────────┐
      │                     │
   REST APIs          WebSocket/STOMP
      │                     │
┌─────┴──────────────────────────┐
│      Spring Security            │
│    (JWT Authentication Filter)  │
└────────────────┬────────────────┘
                 │
┌────────────────┴─────────────────────┐
│         8 Controller Layer            │
│  (User, Post, Comment, Like, Follow) │
└────────────────┬─────────────────────┘
                 │
┌────────────────┴──────────────────┐
│       9 Service Layer             │
│    (Business Logic & Events)      │
└───┬─────────────────────────┬─────┘
    │                         │
    │                    ApplicationEventPublisher
    │                         │
┌───┴──────────────────┐  ┌───┴─────────────────┐
│   PostgreSQL JPA     │  │  RabbitMQ Consumer  │
│    Repository        │  │   (Async Tasks)     │
└───┬──────────────────┘  └─────────────────────┘
    │
┌───┴──────────────┐
│  Redis Cache     │
└──────────────────┘

Also Connected:
- Cloudflare R2 (Image Storage)
- WebSocket Broker (Real-time Notifications)
```

---

## Key Design Patterns

1. **Event-Driven**: Actions trigger async events (likes → notifications)
2. **JWT Security**: Stateless, token-based authentication
3. **Soft Deletes**: Posts marked as deleted, not permanently removed
4. **Lazy Loading**: Relationships load on-demand to optimize queries
5. **DTO Pattern**: Request/Response objects separate from entities
6. **Exception Handling**: Centralized via `GlobalExceptionHandler` and custom `WeiboException`

---

## Technology Stack

- **Framework**: Spring Boot 3.5.7
- **Java Version**: 21
- **Database**: PostgreSQL
- **Cache**: Redis (Lettuce)
- **Message Broker**: RabbitMQ (AMQP)
- **Real-time**: WebSocket with STOMP
- **Security**: JWT (java-jwt 4.4.0)
- **Cloud Storage**: Cloudflare R2 (AWS S3 SDK)
- **Build Tool**: Maven
- **Project Lombok**: For reducing boilerplate code
- **Validation**: Jakarta Bean Validation

---

## Configuration

### Environment Setup

**Prerequisites**:
- PostgreSQL running on `localhost:5432`
- Redis running on `localhost:6379`
- RabbitMQ running (default AMQP configuration)

**Database**:
- URL: `jdbc:postgresql://localhost:5432/weibo`
- Username: `dev`
- Password: `123456`
- Auto-migration: Enabled via `ddl-auto: update`

**Server**:
- Port: `8080`

**File Upload**:
- Max file size: 20MB
- Max request size: 20MB

**JWT**:
- Secret: `BRAYTON_LITE_WEIBO_SECRET_KEY`

---

## Project Structure

```
src/main/java/com/brayton/weibo/
├── WeiboApplication.java          # Entry point
├── common/                         # Utilities
│   ├── ChineseUtil.java
│   ├── FeedRandomizer.java
│   ├── ImagesConverter.java
│   ├── R2Client.java
│   └── TimeUtil.java
├── config/                         # Configuration
│   ├── AppConfig.java
│   ├── RabbitConfig.java
│   ├── RedisConfig.java
│   ├── SecurityConfig.java
│   ├── WebConfig.java
│   ├── WebSocketConfig.java
│   └── security/                   # Security-related configs
├── controller/                     # REST API Endpoints
│   ├── CommentController.java
│   ├── FileController.java
│   ├── FollowController.java
│   ├── GlobalExceptionHandler.java
│   ├── LikeController.java
│   ├── NotificationController.java
│   ├── PostController.java
│   └── UserController.java
├── dto/                            # Data Transfer Objects
│   ├── ApiResponse.java
│   ├── CommentRequest.java
│   ├── CommentResponse.java
│   ├── CreatePostRequest.java
│   ├── ErrorResponse.java
│   ├── LikedPostsResponse.java
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   ├── NotificationResponse.java
│   ├── PostResponse.java
│   ├── PostUpdateRequest.java
│   ├── RegisterRequest.java
│   ├── UnreadCountResponse.java
│   └── UserResponse.java
├── entity/                         # JPA Entities
│   ├── Comment.java
│   ├── FollowRelation.java
│   ├── Like.java
│   ├── Notification.java
│   ├── Post.java
│   └── User.java
├── enums/                          # Enumerations
│   ├── Gender.java
│   ├── PostStatus.java
│   ├── PostType.java
│   └── PostVisibility.java
├── error/                          # Error handling
├── event/                          # Event definitions
├── repository/                     # Data access layer
├── service/                        # Business logic
│   ├── CommentService.java
│   ├── FileService.java
│   ├── FollowService.java
│   ├── JWTService.java
│   ├── LikeService.java
│   ├── NotificationService.java
│   ├── PostService.java
│   ├── RedisService.java
│   └── UserService.java
└── webSocket/                      # WebSocket components
```

---

## Building & Running

### Build
```bash
mvn clean package
```

### Run
```bash
mvn spring-boot:run
```

### Run JAR
```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

---

## API Documentation

The application exposes REST APIs for:
- User authentication and profile management
- Post creation, reading, updating, and deletion
- Comments on posts
- Likes on posts and comments
- Follow/unfollow functionality
- Real-time notifications via WebSocket

All endpoints (except `/login`, `/register`, and `/ws`) require JWT authentication via the `Authorization` header.

---

## License

This project is part of the Lite-Weibo platform.
