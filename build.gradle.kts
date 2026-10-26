plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.3"
}

group = "com.example"
version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencyManagement {
    imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.3") }
}

dependencies {
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("org.mongodb:mongodb-driver-sync")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("org.webjars:swagger-ui:5.33.2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test> { useJUnitPlatform() }

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java", "src/offline/**/*.java")
        forbidWildcardImports()
        removeUnusedImports()
        googleJavaFormat("1.36.1")
    }
}

// 문서 원본을 빌드에 포함해 UI용 별도 명세 복사본을 관리하지 않는다.
tasks.processResources {
    from("docs/openapi") { into("api-docs") }
    from("output/pdf") {
        include("gateway-api-guide.pdf", "backend-integration-spec.pdf")
        into("api-docs")
    }
}

// 외부망 전용. src/offline 삭제 시 이 블록도 함께 제거할 수 있다.
if (file("src/offline").exists()) {
    val offline by sourceSets.creating {
        java.setSrcDirs(listOf("src/offline/java"))
        resources.setSrcDirs(listOf("src/offline/resources"))
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
    configurations[offline.implementationConfigurationName].extendsFrom(configurations.implementation.get())
    configurations[offline.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

    val offlineTest by sourceSets.creating {
        java.setSrcDirs(listOf("src/offline/test/java"))
        resources.setSrcDirs(listOf("src/offline/test/resources"))
        compileClasspath += sourceSets.main.get().output + offline.output
        runtimeClasspath += sourceSets.main.get().output + offline.output
    }
    configurations[offlineTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
    configurations[offlineTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

    tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("bootRunOffline") {
        group = "application"
        description = "외부망 임시 메모리 세션/PFC mock으로 실행"
        classpath = offline.runtimeClasspath
        mainClass.set("com.example.chatgateway.ChatGatewayApplication")
        args("--spring.profiles.active=offline")
    }
    val offlineTestTask = tasks.register<Test>("offlineTest") {
        description = "외부망 대역을 사용하는 회귀 테스트"
        testClassesDirs = offlineTest.output.classesDirs
        classpath = offlineTest.runtimeClasspath
        shouldRunAfter(tasks.test)
    }
    tasks.check { dependsOn(offlineTestTask) }
}
