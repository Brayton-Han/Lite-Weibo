# System Overview
This is a Twitter-like social media platform built with Java Spring Boot 3.5.7, running on Java 21. It's designed to be scalable with event-driven architecture and real-time capabilities.
***
### Core Components
1\. Data Layer (PostgreSQL + JPA)

Entities - Seven core domain models:
- User: Profiles with followers/following counts, avatar, bio, gender, birthday
- Post: The main content unit with support for multiple types (original, repost, etc.)
- Comment: Replies to posts
- Like: Post/comment interactions
- FollowRelation: User relationship management
- Notification: Activity alerts for follows, likes, comments
- PostType, PostVisibility, PostStatus, Gender - Enums for structured data

Key Features:
- Soft deletes on posts (via Hibernate's @SQLDelete)
- JSON columns for image lists (using custom ImagesConverter)
- Lazy-loaded relationships to prevent N+1 query problems
- PostgreSQL at localhost:5432 with auto schema updates via Hibernate

2\. Service Layer (Business Logic)

Nine specialized services handle core functionality:
- PostService: CRUD, visibility rules, building feed responses
- UserService: Authentication, registration, profile management
- CommentService: Comment operations with notifications
- LikeService: Like/unlike with async event publishing
- FollowService: Follow/unfollow with relationship counts
- NotificationService: Notification persistence and retrieval
- FileService: File upload handling
- JWTService: Token generation and validation
- RedisService: Caching layer

The services use event-driven patterns with ApplicationEventPublisher for asynchronous operations (e.g., likes trigger events).

3\. API Layer (REST Controllers)

Eight controllers expose endpoints:
- UserController: /login, /register, profile endpoints
- PostController: Create, read, update, delete posts
- CommentController: Comment operations
- LikeController: Toggle likes
- FollowController: Follow/unfollow
- NotificationController: Fetch notifications
- FileController: File uploads (20MB max)
- GlobalExceptionHandler: Centralized error handling

Authentication: Stateless JWT-based (Strategy: SessionCreationPolicy.STATELESS)

4\. Messaging & Real-Time (RabbitMQ + WebSocket)

RabbitMQ - Topic exchange pattern with 3 queues:
- notification.follow.queue → routes follow events
- notification.like.queue → routes like events
- notification.comment.queue → routes comment events

All bind to notification.exchange with routing keys like notification.follow, notification.like, etc.

WebSocket (STOMP over WebSocket):
- Endpoint: /ws (JWT-authenticated)
- Message Broker: /topic/** for broadcasts, /queue/** for user-specific messages
- Application prefix: /app/** for client→server messages
- Used for real-time push updates via WebSocketPusher

5\. Caching (Redis)
- Host: localhost:6379
- Pool: 8 max active connections
- Timeout: 3 seconds
- Used for caching hot data and reducing database load

6\. Microservice Features

Cloud Storage (Cloudflare R2 - S3-compatible):
- Stores post images and user avatars
- Account ID, access keys configured in application.yml
- Public base URL: https://pub-4ef9f81c6a264c37ae3775f783adb4a2.r2.dev

Docker Support: Included for containerization
***
### Architecture Diagram

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
***
### Key Design Patterns
1. Event-Driven: Actions trigger async events (likes → notifications)
2. JWT Security: Stateless, token-based authentication
3. Soft Deletes: Posts marked as deleted, not permanently removed
4. Lazy Loading: Relationships load on-demand to optimize queries
5. DTO Pattern: Request/Response objects separate from entities
6. Exception Handling: Centralized via GlobalExceptionHandler and custom WeiboException

This architecture supports a scalable social network with real-time features while maintaining separation of concerns.
