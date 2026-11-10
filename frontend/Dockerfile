# syntax=docker/dockerfile:1
# 多阶段构建 + Next.js standalone 输出：运行镜像只含 server.js 及其实际用到的依赖。

# ── 依赖 ──
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

# ── 构建 ──
FROM node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* 在 build 时内联进前端包，运行时再设环境变量不起作用，所以必须是 build arg
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ENV NEXT_TELEMETRY_DISABLED=1

RUN npm run build

# ── 运行 ──
FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# standalone 不包含 public 和 .next/static，需要单独拷贝
COPY --from=build --chown=node:node /app/public ./public
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static

# node 镜像自带的非 root 用户
USER node

EXPOSE 3000

CMD ["node", "server.js"]
