# Repository Guidelines

## Project Structure & Module Organization

- `backend/`: Java 21 / Spring Boot application. Sources live under `src/main/java/com/brayton/weibo/`, organized into controllers, services, repositories, entities, DTOs, configuration, and events. Keep business logic in services and persistence in repositories. Runtime configuration lives in `src/main/resources/application.properties`.
- `frontend/`: Next.js 16, React 19, TypeScript, and Tailwind CSS. Routes live in `src/app/`, reusable UI in `src/components/`, API helpers in `src/lib/`, shared types in `src/types/`, and static assets in `public/`.
- `pika/` and `backend/src/main/java/com/brayton/weibo/test/`: queue throughput tools. Markdown guides in `docs/` document architecture, schemas, concurrency, and performance testing.
- `docker-compose.yml`: PostgreSQL, Redis, RabbitMQ, backend, and frontend services.

## Build, Test, and Development Commands

- From the root, `docker compose up -d --build` builds and starts the full stack; frontend uses port 3000 and backend uses 8080.
- From `backend/`, `./mvnw clean package` builds the JAR and runs available tests; `./mvnw test` runs tests only.
- From `backend/`, `./mvnw spring-boot:run` starts the API; configure reachable PostgreSQL, Redis, and RabbitMQ first.
- From `frontend/`, `npm ci` installs locked dependencies; `npm run dev` starts development, `npm run build` creates a production build, and `npm run lint` runs ESLint.

## Coding Style & Naming Conventions

Use four-space indentation in Java and two spaces in TypeScript/TSX, matching surrounding code. Use PascalCase for classes and React components, camelCase for methods and variables, and UPPER_SNAKE_CASE for constants. Retain descriptive Java suffixes such as `Service`, `Repository`, `Request`, and `Response`. Prefer `@/` frontend imports and explicit shared types. TypeScript strict mode and Next.js ESLint rules are configured; no dedicated formatter is configured.

## Testing Guidelines

Spring Boot's test starter provides JUnit Jupiter and Mockito. No conventional test suite, frontend test script, or coverage threshold is currently configured. Add backend tests under `backend/src/test/java/`, mirroring production packages, with names ending in `Test`.

The comment throughput runner is a Spring component, not a JUnit test. Run `TEST_MODE=comment-throughput ./mvnw spring-boot:run` from `backend/` against local test services; see `docs/QUICKSTART_COMMENT_THROUGHPUT_TEST.md`. Include relevant manual verification for UI changes.

## Commit & Pull Request Guidelines

History uses short descriptive summaries such as `add entity schema`; no mandatory Conventional Commits pattern is evident. Keep commits focused. PRs should explain behavior changes, link relevant issues, report validation commands/results, and include screenshots for UI changes. Document configuration or schema changes. Keep credentials out of commits.
