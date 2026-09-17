plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
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
    // 测试 JVM 的默认上限是 512MB（Gradle 默认），而本套件（800+ 测试、十余个
    // @SpringBootTest 上下文 + 共享 H2 内存库）实测峰值已贴近 512MB：把上限压到
    // 448MB 后、**即使排除全部 wiki 测试**也稳定 OOM。阶段 4.2 加入 wiki 测试后
    // 就变成偶发 OutOfMemoryError（表现为「Gradle Test Executor N failed to
    // execute tests」，随机命中某个测试类，极易误判成业务 bug）。此处显式留余量。
    maxHeapSize = "1g"
}
