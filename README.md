# ragagent-java

WeKnora 的 Java 重构版（Spring Boot 3 + JDK 21）。由 Go 版（[Tencent/WeKnora](https://github.com/Tencent/WeKnora)，本地参考仓 `/Users/billy/WeKnora`）全面翻译而来，团队技术栈统一项目。

## 结构

```
├── server/        Spring Boot 后端（com.ragagent.*，按领域分包）
├── frontend/      Vue 3 前端（从 Go 仓原样复制，与后端只认 HTTP/SSE 契约）
├── migrations/    98 个 SQL 迁移（= Go 仓 migrations/versioned/，schema 一字不改）
├── docreader/     docreader gRPC proto（目录名镜像 Go 仓 docreader/proto/）
├── docker-compose.yml  dev 环境（postgres/redis/docreader）
└── docs/          翻译约定文档（AI 翻译会话必读）
```

## 快速开始

```bash
docker compose up -d          # postgres(15432) + redis(16379) + docreader(50051)
./gradlew bootRun             # 后端 :8080
cd frontend && npm ci && npm run dev   # 前端 :5173，代理到 :8080
```

## 翻译状态

见 `docs/translation-progress.md`。
