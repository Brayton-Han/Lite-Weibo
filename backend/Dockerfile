# syntax=docker/dockerfile:1
# 多阶段构建：镜像内编译，不依赖宿主机预先 mvn package。
# 测试由 CI 跑（.github/workflows/ci.yml），这里 -DskipTests 只负责出包。

# ── 构建阶段 ──
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# 依赖单独一层：只改源码时不用重新下载依赖
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/*.jar /src/app.jar

# ── 运行阶段 ──
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /src/app.jar app.jar
USER app

# JVM 默认堆只占容器内存的 25%；compose 设了内存上限，按 75% 分给堆
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
