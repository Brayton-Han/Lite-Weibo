# Lite-Weibo System Architecture

## Overview

Lite-Weibo is a scalable, cloud-native social media platform inspired by Twitter. It's designed as a multi-tier, event-driven distributed system that supports high-concurrency read/write operations with real-time notifications through WebSocket connections.

The system consists of three main layers:
- **Frontend**: Next.js web application for user interaction
- **Backend**: Spring Boot microservices with RESTful API
- **Infrastructure**: PostgreSQL, Redis, RabbitMQ, Docker containers

---

## Technology Stack

### Frontend
- **Framework**: Next.js 16 (React 19)
- **Language**: TypeScript
- **Styling**: Tailwind CSS 4
- **State Management**: React hooks
- **Real-time Communication**: WebSocket (STOMP.js, SockJS)
- **HTTP Client**: Axios
- **UI Components**: Lucide React icons, React Hot Toast (notifications)
- **Media**: HEIC2Any (image format conversion)

### Backend
- **Framework**: Spring Boot 3.5.7
- **Language**: Java 21
- **Database**: PostgreSQL 15 (JPA/Hibernate ORM)
- **Caching**: Redis 7
- **Message Queue**: RabbitMQ 3 (AMQP)
- **Real-time**: Spring WebSocket with STOMP
- **Authentication**: JWT (java-jwt 4.4.0)
- **File Storage**: AWS S3 (S3 SDK 2.25.13)
- **Validation**: Spring Boot Validation, Lombok

### Infrastructure
- **Containerization**: Docker & Docker Compose
- **Databases**: PostgreSQL, Redis
- **Message Broker**: RabbitMQ with Management UI
- **Ports**:
  - Backend: 8080
  - Frontend: 3000
  - PostgreSQL: 5432
  - Redis: 6379
  - RabbitMQ: 5672 (AMQP), 15672 (Management UI)

### Message Queue Testing (Python)
- **Library**: Pika (RabbitMQ Python client)
- Used for performance testing and latency measurement

---

## Architecture Layers

### 1. Frontend Layer (Next.js)

#### Structure
```
src/
├── app/                          # Next.js app directory (routing)
│   ├── page.tsx                 # Home page
│   ├── login/page.tsx           # Authentication
│   ├── register/page.tsx        # User registration
│   ├── search/page.tsx          # Global search
│   ├── notifications/page.tsx   # Real-time notifications
│   ├── following/page.tsx       # Feed (following users' posts)
│   ├── friends/page.tsx         # Friends management
│   ├── users/page.tsx           # User directory
│   └── user/[id]/               # User profile with nested routes
│       ├── followers/
│       ├── following/
│       ├── friends/
│       └── liked/
├── components/                  # Reusable React components
│   ├── Navbar.tsx              # Navigation header
│   ├── CreatePostWidget.tsx    # Post creation
│   ├── PostCard.tsx            # Post display
│   ├── EditPostModel.tsx       # Post editing
│   ├── RepostModal.tsx         # Repost functionality
│   ├── SearchResults.tsx       # Search result display
│   ├── UserProfileClient.tsx   # User profile display
│   ├── NotificationsClient.tsx # WebSocket notification handler
│   ├── ToastProvider.tsx       # Toast notification provider
│   └── Square.tsx              # Layout component
└── lib/
    ├── api.ts                  # API client configuration
    ├── imageUtils.ts           # Image handling utilities
    └── types/index.ts          # TypeScript type definitions
```

#### Key Features
- **Server-Side Rendering (SSR)**: Leverages Next.js for performance and SEO
- **Real-time Notifications**: WebSocket client (SockJS + STOMP) subscribes to user-specific channels
- **Infinite Scroll**: Implements cursor-based pagination for feeds
- **Image Handling**: Supports HEIC format conversion for iOS images
- **Toast Notifications**: User feedback for actions (created, updated, deleted)
- **Responsive Design**: Tailwind CSS for mobile-first responsive UI

---

### 2. Backend Layer (Spring Boot)

#### Architecture Diagram

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

#### Domain Model
```
User
├── Credentials (username, email, password hash)
├── Profile (avatar, bio, follower/following counts)
├── Relationships (follows, followers, friends)
└── Activities (posts, likes, comments, notifications)

Post
├── Content (text)
├── Media (images via S3/Cloudflare)
├── Metadata (created_at, updated_at, like_count, comment_count)
├── Relations (author, reposts, comments)
└── Social (likes, replication to follower feeds)

Comment
├── Author
├── Parent Post
└── Content

Like
├── User
├── Post or Comment
└── Timestamp

Follow
├── Follower
├── Following
└── Created At

Notification
├── Type (POST, COMMENT, LIKE, FOLLOW, REPOST)
├── Recipient
├── Source (triggering user/post)
└── Metadata (read status)
```

#### Project Structure
```
src/main/java/com/brayton/weibo/
├── WeiboApplication.java               # Spring Boot entry point
├── controller/                         # REST API endpoints
│   ├── UserController.java            # User management (auth, profile, follow)
│   ├── PostController.java            # Post CRUD, timeline, search
│   ├── CommentController.java         # Comment management
│   ├── LikeController.java            # Like/unlike operations
│   ├── FollowController.java          # Follow/unfollow, friend management
│   ├── NotificationController.java    # Notification retrieval
│   ├── FileController.java            # Media upload (S3 signed URLs)
│   └── GlobalExceptionHandler.java    # Centralized error handling
├── service/                            # Business logic
│   ├── UserService.java               # User operations, authentication
│   ├── PostService.java               # Post creation, feed generation, timeline
│   ├── CommentService.java            # Comment operations
│   ├── LikeService.java               # Like/unlike logic
│   ├── FollowService.java             # Follow relationships, queries
│   ├── NotificationService.java       # Notification creation, delivery
│   ├── FileService.java               # S3 signed URL generation
│   ├── JWTService.java                # JWT token generation/validation
│   └── RedisService.java              # Cache operations
├── repository/                         # Data access (Spring Data JPA)
│   ├── UserRepository
│   ├── PostRepository
│   ├── CommentRepository
│   ├── LikeRepository
│   ├── FollowRepository
│   ├── NotificationRepository
│   └── [Entity]Repository interfaces
├── entity/                             # JPA entities (data models)
│   ├── User.java
│   ├── Post.java
│   ├── Comment.java
│   ├── Like.java
│   ├── Follow.java
│   ├── Notification.java
│   └── [Other entities]
├── dto/                                # Data Transfer Objects (API contracts)
│   ├── UserDTO
│   ├── PostDTO
│   ├── CommentDTO
│   ├── NotificationDTO
│   └── [Response/Request DTOs]
├── event/                              # Event-driven architecture
│   ├── Event.java                     # Base event class
│   ├── EventType.enum                 # Event type enumeration
│   ├── CommentEvent.java              # Event fired on comment
│   ├── LikeEvent.java                 # Event fired on like
│   ├── FollowEvent.java               # Event fired on follow
│   └── NotificationListener.java      # Event listener for notifications
├── webSocket/                          # Real-time communication
│   └── WebSocketPusher.java           # WebSocket message broadcaster
├── config/                             # Spring configuration
│   ├── AppConfig.java                 # Application settings
│   ├── security/                      # Spring Security config
│   ├── CorsConfig.java                # CORS configuration
│   ├── RedisConfig.java               # Redis connection pooling
│   ├── RabbitMQConfig.java            # RabbitMQ queue/exchange setup
│   └── WebSocketConfig.java           # WebSocket endpoint configuration
├── common/                             # Utility classes
│   ├── ChineseUtil.java               # Chinese text processing (tokenization)
│   ├── FeedRandomizer.java            # Feed shuffling/recommendation logic
│   ├── ImagesConverter.java           # Image format conversion
│   ├── TimeUtil.java                  # Time/timestamp utilities
│   └── R2Client.java                  # Cloudflare R2 client (S3-compatible)
├── enums/                              # Enumeration types
│   ├── NotificationType.enum          # Notification type constants
│   ├── LikeType.enum                  # Like target types (Post, Comment)
│   └── [Other enums]
└── error/                              # Error handling
    ├── ErrorCode.java                 # Standardized error codes
    ├── BusinessException.java         # Custom exception class
    └── ErrorResponse.java             # Error response structure
```

#### API Endpoints (Summary)
```
Authentication & Users
  POST   /api/auth/register             # User registration
  POST   /api/auth/login                # JWT token generation
  GET    /api/users/:id                 # Get user profile
  PUT    /api/users/:id                 # Update user profile
  GET    /api/users/search              # Search users

Posts
  POST   /api/posts                      # Create post
  GET    /api/posts/:id                 # Get single post
  PUT    /api/posts/:id                 # Edit post
  DELETE /api/posts/:id                 # Delete post
  GET    /api/posts/feed/timeline       # Get user's feed (cursor pagination)
  GET    /api/posts/search              # Search posts (full-text search)

Comments
  POST   /api/posts/:postId/comments    # Create comment
  GET    /api/posts/:postId/comments    # List post comments
  DELETE /api/comments/:id              # Delete comment

Likes
  POST   /api/posts/:postId/likes       # Like a post
  DELETE /api/posts/:postId/likes       # Unlike a post

Follows & Friends
  POST   /api/users/:id/follow          # Follow user
  DELETE /api/users/:id/follow          # Unfollow user
  GET    /api/users/:id/followers       # List followers
  GET    /api/users/:id/following       # List following

Notifications
  GET    /api/notifications             # Get unread notifications
  PUT    /api/notifications/:id/read    # Mark as read

Media
  GET    /api/upload/signed-url         # Get S3 signed URL for upload

WebSocket
  WS     /ws                            # WebSocket endpoint (STOMP)
  SUBSCRIBE /user/queue/notifications   # User-specific notifications
```

---

### 3. Data Storage & Caching

#### PostgreSQL (Primary Database)
- **purpose**: ACID-compliant persistent storage for all domain entities
- **Schema**: User, Post, Comment, Like, Follow, Notification tables
- **Queries**: JPA Repositories with custom JPQL/SQL for complex queries (feeds, search, aggregations)
- **Optimization**: Indexes on frequently queried columns (user_id, created_at, follower relationships)

#### Redis (Cache & Speed Layer)
Used for:
- **Feed Cache**: Pre-computed feeds for active users (hot data)
- **User Session Data**: JWT token blacklisting, temporary session state
- **Post Aggregations**: Like counts, comment counts, engagement metrics
- **Rate Limiting**: API request throttling per user
- **Real-time Presence**: Online user tracking

#### RabbitMQ (Message Queue)
- **Purpose**: Decouple event producers (post creation, likes, follows) from notification consumers
- **Queues**:
  - `notification_queue`: Notifications awaiting delivery to WebSocket
  - `feed_fanout_queue`: (Optional) For distributed feed generation
- **Flow**: 
  1. User action triggers event (e.g., post created)
  2. Event published to RabbitMQ
  3. Event listener consumes message asynchronously
  4. Notification created and pushed to user via WebSocket
  5. Message acknowledged and removed from queue

---

## Data Flow & Communication Patterns

### 1. Timeline Feed Generation (Hybrid Approach)

**Fan-out on Write + Fan-in on Read**

#### Write-Time (When user posts):
```
User Creates Post
└─> Post saved to PostgreSQL
    └─> Event: POST_CREATED published
        └─> For each follower:
            └─> Add post reference to Redis follower feed cache
                (bloom filters to detect duplicates)
                └─> Lazy persist to feed_events table (optional archival)
```

#### Read-Time (When user loads feed):
```
User Requests Timeline
└─> Check Redis cache first (follower feed)
    ├─> Cache HIT: Return cursor-paginated results
    └─> Cache MISS or expired:
        └─> Fan-in: Query PostgreSQL (user's followed posts + cached trending)
            ├─> Apply cursor-based pagination (efficient large datasets)
            ├─> Cache result in Redis with TTL
            └─> Return to client
```

**Trade-offs**:
- **Write amplification**: Reduced (not every follower gets immediate storage copy)
- **Read latency**: Optimized (cache hits serve in milliseconds)
- **Scalability**: Handles large follower graphs with acceptable memory usage

### 2. Real-time Notification Flow

```
User Action (Like, Comment, Follow, etc.)
├─> Action processed in Service layer
├─> Domain Event created (CommentEvent, LikeEvent, etc.)
├─> Event published to RabbitMQ
│   └─> Event serialized to JSON
│       └─> Message sent to notification_queue
├─> AMQP Consumer (NotificationListener) receives
│   ├─> Consumes from notification_queue
│   ├─> Creates Notification entity
│   ├─> Saves to PostgreSQL
│   └─> Publishes to WebSocket channel: /user/{recipientId}/queue/notifications
└─> Client WebSocket receives
    └─> Toast notification shown to recipient
        └─> If user has UI open on that page, updates in real-time
```

### 3. User Authentication & Authorization

```
User Login
└─> POST /api/auth/login
    ├─> Credentials validated (password hash comparison)
    ├─> JWT token generated (com.auth0.jwt)
    ├─> Token embedded with user claims (id, roles, issued_at, expiry)
    └─> Token returned to client (stored in HTTP-only cookie or localStorage)

Subsequent Requests
└─> Client includes Authorization: Bearer {JWT} header
    └─> Spring Security intercepts
        ├─> Token signature verified
        ├─> Claims extracted (user ID, roles)
        └─> Request proceeds with authenticated principal
```

### 4. Media Upload (Secure Direct Upload)

```
User Selects Image
└─> Frontend requests signed URL
    └─> GET /api/upload/signed-url
        └─> Backend generates AWS S3 pre-signed URL
            (valid for 15 minutes, upload-only)
└─> Frontend uploads directly to S3 (bypasses backend)
    ├─> Reduces backend bandwidth
    ├─> Faster upload speeds
    └─> Server-side signing prevents arbitrary uploads
└─> On post creation
    └─> Frontend provides S3 URL
        └─> Backend stores URL reference in Post entity
```

---

## Event-Driven Architecture

### Event System Design

**Base Event Class**:
```java
Event {
    type: EventType (COMMENT, LIKE, FOLLOW, REPOST, etc.)
    sourceUserId: Long
    targetUserId: Long
    targetPostId: Long
    metadata: Map<String, Object>
}
```

**Event Types**:
- `POST_CREATED`: New post published
- `LIKE_ADDED`: User likes post
- `COMMENT_ADDED`: New comment
- `FOLLOW`: User follows another user
- `REPOST`: User reposts another's post

**Event Listener** (NotificationListener.java):
- Annotated with `@EventListener`
- Spring automatically wires event-consumer relationships
- Executes asynchronously for non-blocking processing
- Creates and persists Notification records
- Publishes to WebSocket broadcaster

### Benefits
- **Loose Coupling**: Services don't directly call each other
- **Scalability**: Event processing can be distributed across microservices
- **Testability**: Mock events for unit testing
- **Auditability**: Event log provides system audit trail

---

## Error Handling & API Responses

### Centralized Exception Handling

**GlobalExceptionHandler** intercepts:
- `Exception` → 500 Internal Server Error
- `BusinessException` → 400/403 Bad Request or Forbidden
- `JpaObjectRetrievalFailureException` → 404 Not Found
- `DataIntegrityViolationException` → 400 Duplicate entry

### Error Response Format
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "User with ID 123 does not exist",
    "timestamp": "2026-03-09T10:30:00Z"
  }
}
```

### Typed Error Codes
- `INVALID_CREDENTIALS`: Authentication failed
- `USER_NOT_FOUND`: User doesn't exist
- `POST_NOT_FOUND`: Post doesn't exist
- `UNAUTHORIZED_ACCESS`: User lacks permission
- `DUPLICATE_ENTRY`: Constraint violation
- [50+ more specific error codes]

---

## Scalability & Performance Optimization

### 1. Database Optimization
- **Indexes**: Foreign keys, frequently filtered columns (user_id, created_at)
- **Query Optimization**: JPA fetch strategies (LAZY loading to prevent N+1 queries)
- **Connection Pooling**: HikariCP for efficient database connections
- **Partitioning** (Future): Time-based partitioning for historical data

### 2. Caching Strategy
- **Post Cache**: Redis sorted sets for user feed (last 500 posts per user)
- **User Cache**: User metadata (follower counts, profile info)
- **TTL Invalidation**: Automatic expiry for cache entries
- **Cache Warming**: Background jobs pre-load popular data

### 3. Read Optimization
- **Cursor-Based Pagination**: Efficient for large datasets
  ```
  ?limit=20&cursor=encoded_last_post_id
  ```
  - O(1) query offset vs. O(n) LIMIT/OFFSET
  - Stable under concurrent insertions
  
### 4. Write Optimization
- **Batch Inserts**: Group notifications, likes
- **Asynchronous Processing**: Non-blocking event pipeline
- **Write-Through Cache**: Update cache alongside database

### 5. Network Optimization
- **CORS**: Configured to allow frontend requests
- **WebSocket**: Persistent bidirectional connection (reduces polling overhead)
- **Compression**: Gzip for API responses (Spring Boot auto-configured)

---

## Security

### Authentication
- **JWT (JSON Web Tokens)**: Stateless authentication
  - Issued on login, verified on each request
  - Contains user ID, roles, expiry timestamp
  - Signature prevents tampering

### Authorization
- **Spring Security**: Role-based access control
  - User: Standard permissions (post, comment, like)
  - Admin: (Optional) Moderation, user management

### Data Privacy
- **Password Hashing**: bcrypt (Spring Security's PasswordEncoder)
- **HTTPS**: (Production) All traffic over TLS
- **CORS**: Restricted to known frontend origin
- **SQL Injection Prevention**: JPA parameterized queries

### Media Security
- **S3 Presigned URLs**: Time-limited upload tokens
  - User can only upload their own media
  - No direct S3 credentials exposed to client
- **Content Validation**: File type, size validation

---

## Deployment & Containerization

### Docker Compose Architecture
```
docker-compose.yml orchestrates:
├── PostgreSQL container
│   └─> Mounts pgdata volume (persistent storage)
├── Redis container
│   └─> In-memory caching
├── RabbitMQ container
│   └─> Management UI at 15672
├── Backend Spring Boot container
│   ├─> Depends on: PostgreSQL, Redis, RabbitMQ
│   ├─> Exposes: 8080
│   └─> Env: Database URLs, Redis host, RabbitMQ host
└── Frontend Next.js container
    ├─> Depends on: Backend
    ├─> Exposes: 3000
    └─> Env: NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

### Network & Volumes
- **Network**: Docker creates internal network for service-to-service communication
- **Volumes**: `pgdata` volume persists database between container restarts
- **Environment Variables**: Injected at container startup

---

## Deployment Steps

```bash
# Build and start all services
docker-compose up --build

# Services become available at:
# Frontend: http://localhost:3000
# Backend API: http://localhost:8080
# RabbitMQ Admin: http://localhost:15672 (guest/guest)
# PostgreSQL: localhost:5432
```

---

## Development Workflow

### Frontend Development
```bash
cd frontend
npm install
npm run dev          # Start Next.js dev server on port 3000
```

### Backend Development
```bash
cd backend
./mvnw spring-boot:run  # Start Spring Boot on port 8080
```

### Database Access
```bash
# Connect to PostgreSQL
psql -h localhost -U postgres -d weibo -p 5432

# View RabbitMQ queues
# Navigate to http://localhost:15672 (credentials: guest/guest)
```

---

## Key Features & Implementation

### 1. User Profiles & Relationships
- User registration with email validation
- Profile picture upload to S3
- Follow/unfollow system
- Follower/following lists
- Mutual friend detection

### 2. Posts (the "status" analog)
- Create, edit, delete posts
- Support for media (images via S3)
- Like counting (denormalized in cache)
- Comment threading
- Repost/share functionality
- Post search (full-text on title + content)

### 3. Comments
- Nested comments on posts
- Like comments
- Delete comments (cascade delete with post)
- Mention/tagging (basic implementation)

### 4. Trending & Discovery
- Search posts by keyword (PostgreSQL full-text search)
- Search users by username/bio
- Trending hashtags (optional)
- Follow recommendations (via graph algorithms)

### 5. Real-time Notifications
- **Types**: Like, Comment, Follow, Repost, Mention
- **Delivery**: WebSocket push + database persistence
- **Read Status**: Track notification read/unread state
- **Batching**: Multiple actions → single notification (optional)

### 6. Feed Algorithm
- **Following Timeline**: Posts from followed users
- **Feed Shuffling**: Random ordering (FeedRandomizer class) or chronological
- **Explore Page**: Trending posts (optional)

---

## Performance Metrics (from pika testing)

The `pika/` directory contains RabbitMQ load testing:
- **Producer**: Sends 1500 messages/second to notification_queue
- **Consumer**: Measures latency from publish to consumption
- **Baseline**: Sub-millisecond latency on local setup

This validates the queue's capability to handle notification spikes.

---

## Future Enhancements

1. **Microservices**: Split backend into independent services (User, Post, Notification microservices)
2. **Elasticsearch**: Replace PostgreSQL full-text search for sub-second search latency
3. **Graph Database**: Neo4j for relationship queries (followers, recommendations)
4. **ML Recommendations**: Collaborative filtering for feed personalization
5. **Analytics**: Kafka for event streaming and analytics pipeline
6. **CDN**: CloudFront for static assets and image distribution
7. **Rate Limiting**: Redis-based, per-user API throttling
8. **Horizontal Scaling**: Load balancer (Nginx) in front of multiple backend instances

---

## Summary

Lite-Weibo demonstrates a modern, event-driven social platform architecture:
- **Frontend**: Responsive Next.js web app with real-time WebSocket updates
- **Backend**: Layered Spring Boot services with clear separation of concerns
- **Data Layer**: PostgreSQL for persistence, Redis for caching, RabbitMQ for event distribution
- **Scalability**: Hybrid feed strategy, cursor pagination, async processing
- **Quality**: Centralized error handling, type-safe DTOs, comprehensive validation

The system is containerized, easily deployable, and architecturally sound for scaling to thousands of concurrent users.
