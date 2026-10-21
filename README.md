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
| Search | Keyword search (Chinese via `pg_trgm`, English via `tsvector`), semantic search (hybrid + rerank), streamed on-site Q&A with clickable citations, a retrieval-details panel |

---

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.5, Spring Security + JWT, Spring Data JPA, Flyway, Caffeine, Spring AMQP, STOMP WebSocket, Actuator + Micrometer
- **Frontend**: Next.js 16, React 19, TypeScript, Tailwind CSS 4, Axios, STOMP.js / SockJS
- **Storage & middleware**: PostgreSQL 15 + pgvector / pg_trgm, Redis 7 (AOF), RabbitMQ 3, Cloudflare R2 (images)
- **AI**: Voyage AI (`voyage-4` embeddings, `rerank-3` reranking), Claude (official `anthropic-java` SDK, default model `claude-haiku-5-5`)
- **Load testing**: comment-queue throughput runner (`TEST_MODE=comment-throughput`), RabbitMQ PerfTest

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
Semantic search (/posts/search/semantic, and "related posts" for RESONANCE queries):
query ─┬─ lexical: pg_trgm word_similarity (zh) / ts_rank_cd (en), top-20 ─┐
       └─ vector:  voyage-4 → pgvector HNSW, top-20 ──────────────────────┴─ weighted RRF ─▶ Voyage rerank-3 (top 30) ─▶ top-k
                                                                              (k=10, w=10)    falls back to RRF on failure

Q&A (/posts/search/ask, /posts/search/ask/stream):
query ──▶ IntentClassifier (Claude)
            ├─ RISK        → crisis message and help resources; no retrieval, no generation
            ├─ RESONANCE   → semantic search results (chain above); no generation
            └─ EXPERIENCE  → vector retrieval top-10
                               ├─ top-1 cosine < 0.40 → "no related discussion on the site" (no model call)
                               └─ Claude + citations → streamed answer; each citation links to its source post
```

- **Embeddings**: new and edited posts are embedded incrementally; deleting a post or changing its visibility removes its vectors, and reposts that quote it are re-embedded. `EmbeddingBackfillRunner` does full backfill and has a read-only consistency check.
- **Prompt injection**: post content is treated as untrusted input, with three layers of defense: document-block isolation, system-prompt rules, and an output-side `PromptLeakGuard` that runs before each streamed chunk is sent.
- **Degradation**: when Claude is rate-limited, over the daily budget, failing, or timing out, Q&A returns `DEGRADED` with retrieval results instead of an error. A local keyword list (`RiskKeywordGuard`) still routes risk queries to help resources. If rerank fails, semantic search falls back to RRF ordering.
- **Protection**: Redis token-bucket rate limits (Q&A 20/hour per user, degraded when exceeded; semantic search 60/min, HTTP 429), a daily Claude cost breaker (`ai.budget.daily-usd`, default $5), per-call timeouts (intent 8s, generation 30s), and a 30-minute Redis cache for Q&A results.
- **Observability**: Actuator on management port 8081 with retrieval latency by stage, rerank latency and fallbacks, Claude calls, tokens, prompt-cache hit ratio, Q&A outcomes, rate-limit decisions, and daily cost; a `traceId` is put in the MDC and returned as `X-Trace-Id`.
- **Frontend**: `/search` shows the AI answer card (streamed, with hover previews of the cited text), refusal, risk, and degraded states, plus a collapsible retrieval-details panel showing the lexical top-5, vector top-5, and the fused ranking with each score.

Q&A for EXPERIENCE queries still retrieves with pure vector top-10, not the RRF + rerank chain, because the 0.40 refusal threshold was calibrated on vector scores (see [docs/DESIGN.md](docs/DESIGN.md) ADR-9).

**Eval results** (3,000-post stratified corpus, 80 labeled queries; parameters tuned on train, **test run once**; details in [docs/EVAL.md](docs/EVAL.md)):

| Retrieval strategy (test, 35 answerable queries) | nDCG@10 | MRR | Recall@10 | p50 |
|---|---|---|---|---|
| Original `LIKE` + recency sort | 0.082 | 0.186 | 0.048 | 0.5 ms |
| Lexical (trigram / `ts_rank_cd`) | 0.232 | 0.573 | 0.116 | 1.4 ms |
| Pure vector | 0.819 | 0.986 | 0.450 | 1.8 ms |
| Weighted RRF (k10 N20 w10) | 0.819 | 1.000 | 0.448 | 2.4 ms |
| RRF + Voyage rerank (production semantic search) | **0.848** | **1.000** | **0.479** | 138 ms |

| Generation (test, run once) | Result | Target |
|---|---|---|
| Citation accuracy | 92.4% | > 90% |
| Hallucination rate | 3.8% | < 5% |
| Refusal accuracy (balanced) | 100% | > 90% |
| Risk-intent recall | 100% (19 / 19) | = 100% |
| Answer helpfulness (1–5) | 3.32 | > 3.5 ❌ |

How to read these numbers:
- Almost all of the gain comes from vector retrieval (nDCG +0.587 over lexical, 95% CI [+0.50, +0.67]).
- Hybrid RRF adds nothing over pure vector on this eval set (Δ −0.000; 26 of 35 queries unchanged).
- Rerank was significant on train (+0.036, CI [+0.005, +0.068]). On test it is +0.029, but the CI [−0.003, +0.061] crosses 0.
- The corpus is synthesized in semantic clusters, which favors embeddings. The ordering between strategies is reliable; the absolute values do not transfer to real social data.
- Citation accuracy and hallucination rate come from an LLM judge, and review showed it over-reports problems. Treat those two numbers as upper bounds on error.
- When Claude is unavailable, the local risk keyword list catches only 13 of 19 held-out risk queries.

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
| Actuator (metrics) | http://127.0.0.1:8081/actuator/metrics |

Without `VOYAGE_API_KEY`, posting and keyword search still work, but no embeddings are generated and semantic search is unavailable.
Without a valid `ANTHROPIC_API_KEY`, the app starts normally and Q&A degrades to semantic search results.

Other optional settings: `AI_BUDGET_DAILY_USD` (daily Claude cost cap), `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000`).
`NEXT_PUBLIC_API_BASE_URL` (see `frontend/.env.example`) is inlined at `next build` time, so in Docker it is a build arg, not a runtime variable.

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
| Search | `GET /posts/search?q=` · `GET /posts/search/semantic?q=&k=` · `GET /posts/search/ask?q=` · `GET /posts/search/ask/stream?q=` (SSE) · `GET /posts/search/retrieval?q=&k=` |
| Files | `POST /upload` |
| Real-time | `WS /ws` (STOMP) |

---

## Repository Layout

```
backend/      Spring Boot app; src/main/java/com/brayton/weibo/ is layered into
              controller / service / repository / entity / dto / event / config,
              plus RAG modules: embedding / retrieval / rerank / generation / eval / tools
frontend/     Next.js app (src/app routes, src/components UI, src/lib API helpers)
corpus/       Synthetic part of the eval corpus (manifest.jsonl, 2,100 posts); the 900-post noise layer
              is rebuilt locally from the Leiden Weibo Corpus (CC BY-NC-SA, not redistributed)
eval/         Labeled sets (train / test / intent), labeling guide, agreement notes, results
tools/        Corpus synthesis and eval-set construction scripts
docs/         Design and evaluation docs
```

## Documentation

Most docs are written in Chinese.

| Doc | Contents |
|---|---|
| [docs/EVAL.md](docs/EVAL.md) | Retrieval and generation evals: corpus, eval-set construction, final test numbers, failed attempts, limitations |
| [docs/DESIGN.md](docs/DESIGN.md) | Design decisions (ADRs) for search and Q&A, and known limitations |
| [docs/CORPUS_DESIGN.md](docs/CORPUS_DESIGN.md) | Stratified design of the eval corpus |
| [docs/ENTITY_SCHEMA.md](docs/ENTITY_SCHEMA.md) | Entities and table schemas |
| [docs/CONCURRENCY_OPTIMIZATION.md](docs/CONCURRENCY_OPTIMIZATION.md) | Concurrency issues and optimizations |
| [docs/COMMENT_QUEUE_THROUGHPUT_TEST.md](docs/COMMENT_QUEUE_THROUGHPUT_TEST.md) | Comment-queue throughput test |

## Status

The RAG feature is complete, and the retrieval and generation test sets have each been run once. Known limitations are listed in [docs/DESIGN.md](docs/DESIGN.md#已知局限). The main ones:
- Chinese lexical search uses trigrams, not true word segmentation.
- Long posts are not split into chunks.
- Q&A does not use rerank.
- Only PUBLIC posts are searchable.
- The eval set is small and the corpus is synthetic.

## License

See [LICENSE](LICENSE).
