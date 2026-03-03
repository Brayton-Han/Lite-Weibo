# Lite-Weibo

1. Designed and implemented a scalable, multi-component backend architecture for a Twitter-like social
platform, supporting user authentication, profiles, posts, reposts, comments, likes, follows, feeds, and notification.

2. Built core backend services using Spring Boot and PostgreSQL, with clear domain modeling and RESTful APIs
to support high-concurrency read/write workloads.

3. Designed a hybrid feed architecture (fan-out on write + fan-in on read) leveraging Redis to balance write
amplification and read latency under large follower graphs.

4. Implemented asynchronous, event-driven notification pipelines using message queues and WebSocket to
decouple user actions from delivery, improving throughput and system responsiveness.

5. Applied cursor-based pagination and caching strategies to optimize feed retrieval, reduce database load, and
improve perceived performance for infinite-scrolling feeds.

6. Implemented a global exception handling framework with typed error codes and unified API response schemas,
simplifying frontend error handling and improving debuggability.

7. Designed a secure media upload workflow with server-side signing, validation, and direct-to-Cloudflare
uploads, reducing backend load and improving upload reliability.

8. Containerized backend services using Docker and applied caching and asynchronous processing to improve
system stability under concurrent access.
