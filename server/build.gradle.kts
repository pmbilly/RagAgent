plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
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
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // gRPC（docreader 客户端；proto 在仓库根 proto/ 目录）
    implementation("net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")   // grpc 生成代码的 @Generated 注解

    // DuckDB（数据分析，对照 Go internal/application/service/chat_pipeline/data_analysis.go）
    implementation("org.duckdb:duckdb_jdbc:1.1.3")

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

// Flyway 要求 V<version>__<desc>.sql 命名；Go 迁移是 000097_xxx.up.sql。
// 这里在构建期生成 Flyway 命名的副本（内容一字不改），canonical 文件留在 migrations/versioned/
val syncMigrations = tasks.register<Copy>("syncMigrations") {
    from("$rootDir/migrations/versioned") {
        include("*.up.sql")
        rename { name ->
            val m = Regex("""0*(\d+)_([a-z0-9_]+)\.up\.sql""").matchEntire(name)
                ?: return@rename name
            "V${m.groupValues[1]}__${m.groupValues[2]}.sql"
        }
    }
    into(layout.buildDirectory.dir("generated-migrations"))
}
tasks.named("processResources") { dependsOn(syncMigrations) }

tasks.withType<Test> {
    useJUnitPlatform()
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
    // 波 3 协作/agents/browserskill 三批（每批 +1-2 个上下文变体）后 4g 在
    // MyBatis XML 解析处 OOM：提到 5g。曲线：512MB→1g→2g→3g→4g→5g。
    maxHeapSize = "5g"
}
