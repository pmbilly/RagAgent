# ragagent-java 服务端镜像（对照 Go 仓 docker/Dockerfile.app 的角色）。
# 构建：docker build -t ragagent-server .
# 运行：环境变量见 .env.example（DB_*/REDIS_*/SYSTEM_AES_KEY/DOCREADER_ADDR）。
#
# 多阶段：
#   1. gradle+JDK21 构建 bootJar（含 proto 生成、migrations 同步）
#   2. JRE 21 运行时
# 前端是独立的 nginx 容器（frontend/Dockerfile），与本镜像仅通过 HTTP/SSE 通信。

FROM gradle:8.14-jdk21 AS builder
WORKDIR /build

# 先拷贝构建脚本与依赖清单，最大化层缓存
COPY settings.gradle.kts build.gradle.kts gradlew gradlew.bat ./
COPY gradle ./gradle
COPY docreader ./docreader
COPY migrations ./migrations
COPY server ./server
# 产物名随 Gradle 默认规则（server-0.0.1-SNAPSHOT.jar），用 glob 排除 -plain.jar；
# cache mount 让重复构建复用依赖与 wrapper 发行版（BuildKit）。
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:bootJar --no-daemon -x test \
    && cp "$(find server/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -1)" /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行（-m 建 home，与 Go 镜像（useradd -m）同形，避免依赖 HOME 的子进程异常）
RUN useradd -m -s /bin/bash --uid 1001 ragagent

COPY --from=builder /app.jar app.jar


USER ragagent

# 环境变量：DB_*/REDIS_*/SYSTEM_AES_KEY/DOCREADER_ADDR/STREAM_MANAGER_TYPE/
# RETRIEVE_DRIVER/BUILTIN_MODELS_CONFIG —— 见 .env.example
ENV DB_HOST=localhost \
    DB_PORT=5432 \
    DB_NAME=WeKnora \
    REDIS_HOST=localhost \
    REDIS_PORT=6379 \
    DOCREADER_ADDR=localhost:50051

EXPOSE 8080
# 健康检查：JRE 镜像无 curl/wget，用 bash 的 /dev/tcp 直连健康端口
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
