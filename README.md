# Lite-Weibo

A Weibo-style social platform built with Spring Boot + Next.js on PostgreSQL / Redis / RabbitMQ, started with a single Docker Compose command.
Beyond the usual social features, the project focuses on two things:

1. **High-concurrency read/write paths**: hybrid push/pull feed, two-level post snapshot cache, Redis like counters with async write-back, and events published only after the transaction commits.
2. **Semantic search and cited Q&A (RAG)**: pgvector retrieval + intent routing + Claude citations, backed by a purpose-built corpus and labeled eval set. Every claim comes with reproducible numbers (see [docs/EVAL.md](docs/EVAL.md)).

---

## Features

| Area | What's included |
|---|---|
| Users | Register / login (JWT), profile and avatar editing, user directory |
| Relationships | Follow / unfollow; follower, following, and mutual-friend lists |
| Posts | Create (multiple images, HEIC supported), edit, delete, repost; visibility: public / followers / friends / private |
| Feed | Latest square, following-based recommendations, friend circle; cursor pagination |
| Interactions | Likes, comments, comment deletion, liked-posts list |
| Notifications | Follow / like / comment notifications, unread badge, real-time WebSocket push |
| Search | Keyword search (Chinese via `pg_trgm`, English via `tsvector`), semantic search, on-site Q&A |

---

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.5, Spring Security + JWT, Spring Data JPA, Flyway, Caffeine, Spring AMQP, STOMP WebSocket
- **Frontend**: Next.js 16, React 19, TypeScript, Tailwind CSS 4, Axios, STOMP.js / SockJS
- **Storage & middleware**: PostgreSQL 15 + pgvector / pg_trgm, Redis 7 (AOF), RabbitMQ 3, Cloudflare R2 (images)
- **AI**: Voyage AI (`voyage-4` embeddings, `rerank-3` reranking), Claude (official `anthropic-java` SDK, default model `claude-haiku-5-5`)
- **Load testing**: Python Pika (`pika/`), comment-queue throughput runner

---

## Architecture

```
 Next.js (3000) ──REST──▶ Spring Boot (8080) ◀──STOMP /ws── Browser
                               │
        ┌──────────────┬───────┼─────────────┬──────────────────┐
        ▼              ▼       ▼             ▼                  ▼
   PostgreSQL        Redis   RabbitMQ   Cloudflare R2     Voyage / Claude
   tables + vectors  feeds,  notif.       images          embed / rerank /
   (pgvector)        caches, queues                       generate
                     counters
```

The backend is layered controller → service → repository. Side effects such as MQ messages, feed fan-out, and cache/counter updates always run after the transaction commits
(via `@TransactionalEventListener(AFTER_COMMIT)` or the [AfterCommit](backend/src/main/java/com/brayton/weibo/common/AfterCommit.java) helper),
so a rollback never leaves behind ghost notifications or feed entries pointing to nonexistent posts.

### Key read/write designs

- **Hybrid push/pull feed** ([FeedWriteService](backend/src/main/java/com/brayton/weibo/service/FeedWriteService.java)):
  regular authors fan out new posts to followers' Redis inboxes. Authors with ≥ `feed.pull.follower-threshold` followers (default 10000) switch to pull mode:
  they only write to their own outbox, which followers merge in at read time.
- **Two-level post snapshot cache** ([PostCacheService](backend/src/main/java/com/brayton/weibo/service/PostCacheService.java)):
  Caffeine → Redis → DB. Edits and deletes use delayed double deletion; comment/repost counts are updated in place with a Lua `HINCRBY`; null markers prevent cache penetration, randomized TTLs prevent avalanches, and Redis failures fall back to the database.
- **Like counts** ([LikeCountService](backend/src/main/java/com/brayton/weibo/service/LikeCountService.java)):
  the `likes` table is the source of truth, the Redis counter is its real-time projection, and a dirty set is periodically flushed in batches to `posts.like_count`, so hot posts no longer contend for a row lock.
- **Notifications**: domain events → RabbitMQ (follow / like / comment queues) → persisted → pushed to online users over WebSocket.
- **Schema**: managed by Flyway (`V1`–`V7`) with `ddl-auto: validate`.

### Semantic search and Q&A (RAG)

```
query ──▶ IntentClassifier (Claude)
            ├─ RISK        → crisis message and help resources; no retrieval, no generation
            ├─ RESONANCE   → semantic search results; no generation
            └─ EXPERIENCE  → vector retrieval top-k
                               ├─ top-1 cosine < 0.40 → "no related discussion on the site" (no model call)
                               └─ Claude + citations → answer with source-post citations on every sentence
```

- New and edited posts are embedded incrementally and deleted posts are cleaned up; full backfill and consistency checks are available (`EmbeddingBackfillRunner`).
- Post content is treated as untrusted input, with three layers of defense: document-block isolation, system-prompt rules, and an output-side `PromptLeakGuard`.
- The retrieval layer also implements `ts_rank`, weighted RRF hybrid, and Voyage rerank strategies (`retrieval/`). They are currently used for evaluation only and are not yet wired into production endpoints.

**Eval results** (3,000-post stratified corpus, 80 labeled queries, train/test split; details in [docs/EVAL.md](docs/EVAL.md)):

| Retrieval strategy (train) | nDCG@10 | MRR | Recall@10 | p50 |
|---|---|---|---|---|
| Original `LIKE` + recency sort | 0.082 | 0.229 | 0.031 | 1.0 ms |
| Lexical `ts_rank` / trigram | 0.257 | 0.675 | 0.104 | 1.8 ms |
| Pure vector (production) | 0.766 | 0.941 | 0.386 | 2.9 ms |
| Weighted RRF (k10 N20 w10) | 0.771 | 0.956 | 0.386 | 2.8 ms |
| RRF + Voyage rerank | **0.807** | **1.000** | 0.395 | 123 ms |

| Generation (test, run once) | Result | Target |
|---|---|---|
| Citation accuracy | 92.4% | > 90% |
| Hallucination rate | 3.8% | < 5% |
| Refusal accuracy (balanced) | 100% | > 90% |
| Risk-intent recall | 100% (19 / 19) | = 100% |
| Answer helpfulness (1–5) | 3.32 | > 3.5 ❌ |

RRF's gain over pure vector is within the confidence interval and not significant; rerank's gain is significant (nDCG +0.036, 95% CI [+0.005, +0.068]).
Citation accuracy and hallucination rate are judged by an LLM judge; manual review found it over-reports problems, so treat those two numbers as upper bounds on error.

---

## Quick Start

Requires Docker Desktop; local development additionally needs JDK 21 and Node.js.

```bash
# 1. Backend config (application.yml is gitignored)
cp backend/src/main/resources/application.yml.example backend/src/main/resources/application.yml
#    fill in jwt.secret, R2 credentials, etc.

# 2. Optional: keys for semantic search / Q&A, passed only via environment variables
export VOYAGE_API_KEY=...
export ANTHROPIC_API_KEY=...

# 3. Start everything
./start.sh                 # build the backend JAR and start all services
./start.sh --skip-build    # start using the existing JAR
./start.sh logs | down | clean | backfill
```

`docker compose up -d --build` works too. Once running:

| Service | Address |
|---|---|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| RabbitMQ management UI | http://localhost:15672 (guest / guest) |
| PostgreSQL / Redis | localhost:5432 / localhost:6379 |

Without `VOYAGE_API_KEY`, posting still works but no embeddings are generated; without `ANTHROPIC_API_KEY`, the app starts normally but Q&A is unavailable.

### Local development

```bash
cd backend && ./mvnw spring-boot:run     # needs reachable PostgreSQL / Redis / RabbitMQ
cd backend && ./mvnw test                # unit tests
cd frontend && npm ci && npm run dev     # dev server
cd frontend && npm run lint
```

### Offline tools (`tools` profile)

Each tool is toggled by an environment variable; run from `backend/` with `... ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools`:

| Switch | Purpose |
|---|---|
| `CORPUS_IMPORT=true` | Import the eval corpus from `corpus/` |
| `EMBEDDING_BACKFILL=true` | Fully backfill / reconcile post embeddings |
| `EVAL_RUN=true` | Retrieval eval (`EVAL_STRATEGIES=like,tsrank,vector,rrf,rerank-voyage`) |
| `GEN_EVAL=answers,judge,intent,summary` | Generation-layer eval |
| `FEED_BACKFILL=true` (no tools profile needed) | Rebuild Redis feeds from PostgreSQL |

For the comment-queue throughput test, see [docs/QUICKSTART_COMMENT_THROUGHPUT_TEST.md](docs/QUICKSTART_COMMENT_THROUGHPUT_TEST.md).

---

## API Overview

All endpoints except `/register`, `/login`, `/ping`, and `/ws/**` require `Authorization: Bearer <JWT>`.

| Area | Endpoints |
|---|---|
| Users | `POST /register` · `POST /login` · `GET /user/{id}` · `GET /users` · `PUT /set` |
| Relationships | `POST/DELETE /follow/{id}` · `GET /user/{id}/following` · `/followers` · `/friends` |
| Posts | `POST /posts` · `PUT /posts/{id}` · `DELETE /posts/{pid}` · `GET /user/{uid}/posts` · `GET /user/{uid}/liked` |
| Feed | `GET /posts` (latest) · `GET /posts/following` · `GET /posts/friends` |
| Interactions | `POST/DELETE /posts/{pid}/like` · `GET/POST /posts/{postId}/comments` · `DELETE /comments/{cid}` |
| Notifications | `GET /notification/unread-count` · `POST /notification/mark-read` · `GET /notification/list` |
| Search | `GET /posts/search?q=` · `GET /posts/search/semantic?q=&k=` · `GET /posts/search/ask?q=` |
| Files | `POST /upload` |
| Real-time | `WS /ws` (STOMP) |

---

## Repository Layout

```
backend/      Spring Boot app; src/main/java/com/brayton/weibo/ is layered into
              controller / service / repository / entity / dto / event / config,
              plus RAG modules: embedding / retrieval / rerank / generation / eval / tools
frontend/     Next.js app (src/app routes, src/components UI, src/lib API helpers)
corpus/       3,000-post stratified eval corpus (manifest*.jsonl)
eval/         Labeled sets (train / test / intent), labeling guide, agreement notes, results
tools/        Corpus synthesis and eval-set construction scripts
pika/         RabbitMQ throughput / latency test scripts
docs/         Design and evaluation docs
```

## Documentation

Most docs are written in Chinese.

| Doc | Contents |
|---|---|
| [docs/RAG_ROADMAP.md](docs/RAG_ROADMAP.md) | RAG implementation roadmap (18-day plan and progress) |
| [docs/EVAL.md](docs/EVAL.md) | Retrieval and generation evals: methodology, numbers, analysis |
| [docs/CORPUS_DESIGN.md](docs/CORPUS_DESIGN.md) | Stratified design of the eval corpus |
| [docs/ENTITY_SCHEMA.md](docs/ENTITY_SCHEMA.md) | Entities and table schemas |
| [docs/CONCURRENCY_OPTIMIZATION.md](docs/CONCURRENCY_OPTIMIZATION.md) | Concurrency issues and optimizations |
| [docs/COMMENT_QUEUE_THROUGHPUT_TEST.md](docs/COMMENT_QUEUE_THROUGHPUT_TEST.md) | Comment-queue throughput test |
| [docs/PROJECT_ANALYSIS.md](docs/PROJECT_ANALYSIS.md) | Overall project analysis |

## Status

The RAG roadmap is complete through Day 15 (corpus, embeddings, evaluation, hybrid retrieval, rerank, intent routing, cited Q&A, generation eval). Remaining:

- Day 16: wire semantic search and Q&A into the frontend; stream generated answers
- Day 17: put rerank into production (with fallback); cost, rate limiting, observability
- Day 18: DESIGN.md and final documentation

## License

See [LICENSE](LICENSE).
