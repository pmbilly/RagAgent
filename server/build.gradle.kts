plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
    id("com.diffplug.spotless")
}

// 格式卫生起步配置（阶段 0）：ratchet 自 seed 起，只检查后续触碰过的文件——
// 裁剪手术期不做全仓格式化大爆炸（红线：一次只动一个轴）；全量 formatter 留阶段 3+。
spotless {
    java {
        ratchetFrom("seed")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

/**
 * /system/info 的构建信息注入（对照 Go 的 ldflags 机制，Java 侧等价物）：
 * Spring Boot build-info.properties 在构建期生成（META-INF/），dev/生产都有真实值，
 * 不像 Go 缺 ldflags 时恒 "unknown"。version 显式对齐前端 package.json 的 0.8.0
 * —— 否则前端按「后端版本 != 前端版本」显示「版本不匹配」告警。
 */
fun gitShortCommit(): String = try {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        workingDir = rootDir
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
} catch (_: Exception) {
    "unknown" // 非 git 检出（如打包好的源码树）→ 与 Go 缺 ldflags 同形
}

springBoot {
    buildInfo {
        properties {
            version = "0.8.0"
            additional = mapOf("commitId" to gitShortCommit())
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}


dependencies {
    // Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // 数据库
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter:3.5.7")
    implementation("org.postgresql:postgresql")   // PGobject 等编译期可见（VectorStoreService）
    // 图库面（D 批）：对照 Go repository/retriever/neo4j（neo4j-go-driver）。
    // NEO4J_ENABLE 未启用时驱动为 null，仓储全部操作为 no-op——与 Go 的 nil driver 分支一致。
    implementation("org.neo4j.driver:neo4j-java-driver:5.28.5")
    // A3 存储后端（批次一）：S3 协议族（s3/minio/obs/ks3）。对照 Go：
    //   s3  → aws-sdk-go-v2/service/s3
    //   obs → aws-sdk-go-v2 + obsEndpointResolver（S3 兼容端点，Go 自己就是这条路）
    //   ks3 → ks3sdklib/aws-sdk-go（金山云的 AWS SDK 分支）
    //   minio → minio-go/v7（Java 侧用 S3 协议客户端，MinIO 兼容 S3；差异逐条备案）
    // 版本要求：≥2.30（才有 requestChecksumCalculation —— Go 用 RequestChecksumCalculationWhenRequired
    // 放宽尾校验和协商，S3 兼容服务（MinIO/OBS/KS3）常拒绝默认协商）
    implementation("software.amazon.awssdk:s3:2.31.68")
    // A3 存储后端（批次二）：厂商原生 SDK，逐个对齐 Go 的 oss.go / cos.go / tos.go
    //   oss → Go: aliyun/alibabacloud-oss-go-sdk-v2   → Java: com.aliyun.oss:aliyun-sdk-oss
    //   cos → Go: tencentyun/cos-go-sdk-v5            → Java: com.qcloud:cos_api
    //   tos → Go: volcengine/ve-tos-golang-sdk/v2     → Java: com.volcengine:ve-tos-java-sdk
    implementation("com.aliyun.oss:aliyun-sdk-oss:3.18.1")
    implementation("com.qcloud:cos_api:5.6.227")
    implementation("com.volcengine:ve-tos-java-sdk:2.9.19")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // gRPC（docreader 客户端；proto 在仓库根 proto/ 目录）
    implementation("net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")   // grpc 生成代码的 @Generated 注解

    // DuckDB（数据分析，对照 Go internal/application/service/chat_pipeline/data_analysis.go）
    implementation("org.duckdb:duckdb_jdbc:1.1.3")

    // SQLite 检索引擎（W5γ4.16）：照 Go 的 mattn/go-sqlite3（CGO）口径换成 xerial 的纯 JDBC
    // 驱动——平台 native 由 Maven 构件自带（仓内零二进制）。实测 3.46.1 打包版支持
    // FTS5 / contentless_delete / bm25()（关键词面与 Go 同构）。
    // Go 侧无对应依赖（其 sqlite 走产品库的 GORM），故此依赖是本仓的"介质替身"。
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // Doris 检索引擎（W5γ4.10）：MySQL 协议主链路。对照 Go go-sql-driver/mysql
    // （container.go 的 _ "github.com/go-sql-driver/mysql" 注册 + createDorisEngine 的
    // sql.Open）；Stream Load（HTTP/8030）由 DorisStreamLoadClient 自持，不用 SDK。
    // 版本由 Spring Boot BOM 管理。
    implementation("com.mysql:mysql-connector-j")

    // JWT（对照 internal/middleware/auth.go）
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // bcrypt（对照 Go golang.org/x/crypto/bcrypt，DefaultCost=10）
    implementation("org.springframework.security:spring-security-crypto")

    // tiktoken BPE 编码器（对照 Go github.com/tiktoken-go/tokenizer 的 cl100k_base，
    // internal/agent/token/estimator.go 的逐字节等价物；encodeOrdinary = Go 的 Encode——
    // 两者对特殊 token（<|endoftext|> 等）都不做特殊处理，纯 BPE。纯 Java 零传递依赖）
    implementation("com.knuddels:jtokkit:1.1.0")

    // Docker Engine API 客户端（对照 Go github.com/docker/docker/client，
    // internal/sandbox/docker_engine.go 的 dockerEngineAPI 接口）。httpclient5 transport
    // 支持 unix://（Java 16+ UnixDomainSocketAddress）与 TCP+TLS，exec hijack 流式输出
    // 由其 ExecStartCmd 回调承载——与 Go client.ContainerExecAttach 同一传输语义。
    implementation("com.github.docker-java:docker-java-core:3.7.1")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.7.1")

    // 工具
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // 测试
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 架构规则（B10）：把 B6 等批次攒下的约定固化成测试套件里的红条（CI 的 ./gradlew build 即闸门）
    testImplementation("com.tngtech.archunit:archunit:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")   // 契约/单元测试内存库，不依赖外部 postgres
}

// proto 源目录指向仓库根 docreader/（单一来源，不复制；目录名镜像 Go 仓 docreader/proto/）
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.66.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { create("grpc") }
        }
    }
}
sourceSets["main"].proto {
    srcDir("../docreader")
    // OTLP/OTel proto（langfuse OTLP/HTTP 导出用）：源头 vendored 到仓库根 otlp-proto/，
    // 取自 open-telemetry/opentelemetry-proto v1.10.0 的 common/resource/trace 三文件，
    // 一字未改（java_package 保持官方 io.opentelemetry.proto.*——fat jar 内无同名依赖）。
    srcDir("$rootDir/otlp-proto")
}

// Flyway 要求 V<version>__<desc>.sql 命名。迁移已基线化为 migrations/versioned/V1__baseline.sql
// （历史增量 000097_xxx.up.sql 时代在构建期改名副本；2026-09-29 起 V1 基线直通，
// 后续增量直接以 Flyway 命名入库即可）。
val syncMigrations = tasks.register<Copy>("syncMigrations") {
    from("$rootDir/migrations/versioned") {
        include("V*.sql")
    }
    into(layout.buildDirectory.dir("generated-migrations"))
}
tasks.named("processResources") { dependsOn(syncMigrations) }

tasks.withType<Test> {
    useJUnitPlatform()
    // Mockito inline 在 JDK 21+（homebrew OpenJDK 拒绝自挂 attach）会按
    // MockitoInitializationException 批量假失败：把 byte-buddy-agent 显式挂为
    // javaagent——其 Installer premain 先行装入 Instrumentation，Mockito 的
    // ByteBuddyAgent.install() 检测到已装好的 instrumentation 后不再走 attach
    val byteBuddyAgent = configurations.testRuntimeClasspath.get().files
        .firstOrNull { it.name.startsWith("byte-buddy-agent-") }
    if (byteBuddyAgent != null) {
        jvmArgs("-javaagent:$byteBuddyAgent")
    }
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // 测试 JVM 的默认上限是 512MB（Gradle 默认），而本套件（十余个 @SpringBootTest
    // 上下文 + 共享 H2 内存库）实测峰值已贴近 512MB：把上限压到 448MB 后、
    // **即使排除全部 wiki 测试**也稳定 OOM。阶段 4.2 加入 wiki 测试后就变成偶发
    // OutOfMemoryError（表现为「Gradle Test Executor N failed to execute tests」，
    // 随机命中某个测试类，极易误判成业务 bug）。
    //
    // 阶段 5.2/波 0 把套件推到 2855 条后，1g 也不够了——**波 0 的 datasource 连接器层
    // 一次加了 737 条测试，1g 下全量稳定 OOM（单跑该包 829 条没事，所以很容易漏）**。
    // 提到 2g。这是**测试期参数，不影响生产**；再翻一倍测试量时记得继续往上调。
    //
    // 波 2 终扫批（3273 条 + 契约文件过千）后 2g 再次随机 OOM（仍表现为
    // 「Gradle Test Executor N failed to execute tests」，OOM 点在 Spring 资源扫描）：
    // 提到 3g。波 3 sandbox 子批 1（+13 个 MockMvc 测试、+2 个上下文变体）后 3g
    // 又在 GC 死亡螺旋后 OOM（全量耗时 6.7min→13.4min 即螺旋特征）：提到 4g。
    // 治本方向是收敛 @SpringBootTest 上下文变体数量（每变体整份上下文驻留堆）。
    // 历史批次累积后 4g 在
    // MyBatis XML 解析处 OOM：提到 5g。曲线：512MB→1g→2g→3g→4g→5g。
    maxHeapSize = "5g"

    // 测试沙箱化：docreader 地址一律置空。SystemContractTest.parserEnginesOfflineShape
    // 断言 connected=false，而该端点读 System.getenv("DOCREADER_ADDR")——调用者 shell 里若
    // source 过 scripts/dev-env.sh（会导出 localhost:50051），测试 JVM 继承后连上真 docreader
    // 就**假红**（2026-09-25 踩过）。空串与未设等价（端点按 null/空归一成 ""）。
    environment("DOCREADER_ADDR", "")

    // 契约夹具重录开关（换锚批用）：`-Dcontract.refresh=true` 时，支持该开关的契约测试
    // 把**掩码后的实际响应**写回 src/test/resources/contracts（平时是断言）。Gradle 的 -D
    // 只作用于 daemon JVM，必须显式转发给 fork 出来的测试 JVM，否则测试读不到。
    systemProperty("contract.refresh", System.getProperty("contract.refresh") ?: "false")
}
