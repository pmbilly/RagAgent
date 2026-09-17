plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

allprojects {
    group = "com.ragagent"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
