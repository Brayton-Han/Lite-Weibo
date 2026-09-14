#!/bin/bash

# Lite-Weibo 一键启动脚本
# 用法:
#   ./start.sh              构建后端 jar 并启动全部服务
#   ./start.sh --skip-build 跳过 Maven 打包，直接用现有 jar 启动
#   ./start.sh down         停止并移除容器（保留数据库数据）
#   ./start.sh clean        停止并移除容器 + 删除 pgdata / redisdata 数据卷
#   ./start.sh logs         跟踪 backend / frontend 日志
#   ./start.sh backfill     从 PostgreSQL 回灌 Redis feed 缓存（可重复执行）

set -euo pipefail

cd "$(dirname "$0")"

SKIP_BUILD=false
ACTION="up"

case "${1:-}" in
    down|stop)      ACTION="down" ;;
    clean)          ACTION="clean" ;;
    logs)           ACTION="logs" ;;
    backfill)       ACTION="backfill" ;;
    --skip-build)   SKIP_BUILD=true ;;
    "")             ;;
    *)
        echo "❌ 未知参数: $1"
        echo "可用: (空) | --skip-build | down | clean | logs | backfill"
        exit 1
        ;;
esac

# Compose V2 (docker compose) 优先，回退到 V1 (docker-compose)
if docker compose version > /dev/null 2>&1; then
    COMPOSE="docker compose"
elif command -v docker-compose > /dev/null 2>&1; then
    COMPOSE="docker-compose"
else
    echo "❌ 未找到 docker compose，请先安装 Docker Desktop"
    exit 1
fi

if ! docker info > /dev/null 2>&1; then
    echo "❌ Docker 守护进程未运行，请先启动 Docker Desktop"
    exit 1
fi

case "$ACTION" in
    down)
        echo "🛑 正在停止服务..."
        $COMPOSE down
        echo "✅ 已停止（数据库数据保留在 pgdata 卷中）"
        exit 0
        ;;
    backfill)
        if ! docker compose ps --status running --services 2>/dev/null | grep -qx redis; then
            echo "❌ Redis 未运行，请先执行 ./start.sh"
            exit 1
        fi
        echo "🔄 正在回灌 feed 缓存（复用线上扇出规则，可安全重跑）..."
        # 一次性容器，跑完即退。compose run 默认不发布端口，不会和运行中的 backend 抢 8080
        $COMPOSE run --rm --no-deps -e FEED_BACKFILL=true backend
        exit 0
        ;;
    clean)
        echo "⚠️  这会删除 pgdata 和 redisdata 数据卷，数据库与缓存内容将全部丢失。"
        read -p "确认继续? (y/N): " confirm
        if [ "${confirm:-n}" != "y" ]; then
            echo "已取消"
            exit 0
        fi
        $COMPOSE down -v
        echo "✅ 已停止并清空数据"
        exit 0
        ;;
    logs)
        $COMPOSE logs -f backend frontend
        exit 0
        ;;
esac

echo "🚀 Lite-Weibo 一键启动"
echo "=================================="
echo ""

# backend/Dockerfile 只做 COPY target/*.jar，镜像内不编译，
# 所以必须先在宿主机打包，否则会把旧 jar 打进镜像。
if [ "$SKIP_BUILD" = true ]; then
    if ! ls backend/target/*.jar > /dev/null 2>&1; then
        echo "❌ backend/target 下没有 jar，无法跳过构建"
        exit 1
    fi
    echo "⏭️  跳过 Maven 打包，使用现有 jar:"
    ls -1 backend/target/*.jar | sed 's/^/     /'
else
    echo "📦 正在打包后端 (./mvnw clean package -DskipTests)..."
    (cd backend && ./mvnw -q clean package -DskipTests)
    echo "✅ 打包完成: $(ls -1 backend/target/*.jar | head -1)"
fi

echo ""
echo "🐳 正在构建并启动容器..."
$COMPOSE up -d --build

# 无 actuator，以「端口能返回任意 HTTP 状态码」作为就绪信号
wait_for() {
    local name=$1 url=$2 timeout=${3:-120} elapsed=0
    printf "⏳ 等待 %s 就绪" "$name"
    while [ $elapsed -lt "$timeout" ]; do
        if curl -s -o /dev/null --max-time 2 "$url"; then
            printf " ✅ (%ss)\n" "$elapsed"
            return 0
        fi
        printf "."
        sleep 3
        elapsed=$((elapsed + 3))
    done
    printf " ⚠️  超时 (%ss)，请查看日志: ./start.sh logs\n" "$timeout"
    return 1
}

echo ""
wait_for "后端 API" http://localhost:8080 120 || true
wait_for "前端" http://localhost:3000 120 || true

echo ""
echo "=================================="
echo "✅ 启动完成，服务地址:"
echo ""
echo "   前端            http://localhost:3000"
echo "   后端 API        http://localhost:8080"
echo "   RabbitMQ 控制台 http://localhost:15672   (guest / guest)"
echo "   PostgreSQL      localhost:5432          (postgres / 123456)"
echo "   Redis           localhost:6379"
echo ""
echo "   回灌缓存  ./start.sh backfill"
echo "   查看日志  ./start.sh logs"
echo "   停止服务  ./start.sh down"
echo ""
