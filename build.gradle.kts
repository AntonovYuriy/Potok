plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
    // Retry rarely-flaky tests automatically. Spring's @SpringBootTest random
    // port allocation occasionally races with a port that another (caching)
    // test context just freed; the bytes arrive garbled and `RestTemplate`
    // surfaces them as `parsing HTTP/1.1 status line, receiving [??????…]`.
    // One retry is enough to absorb the rare collision without masking real
    // failures — maxFailures still fails the build fast on widespread breakage.
    id("org.gradle.test-retry") version "1.6.2"
}

group = "io.potok"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.rometools:rome:2.1.0")
    implementation("com.jayway.jsonpath:json-path:2.9.0")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Templates are the single source of truth. They ship in the jar for the
// Help form-import UI; examples/ are GENERATED from them (renderExamples)
// with the manifest defaults, committed, and drift-checked by a test.
tasks.processResources {
    from("examples") {
        into("static/help/examples")
    }
    from("templates") {
        into("static/help/templates")
    }
}

tasks.register<JavaExec>("renderExamples") {
    group = "build"
    description = "Regenerate examples/ from templates/ with manifest defaults"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.potok.template.TemplateRenderer"
    args(projectDir.absolutePath)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Path of the docker socket as seen by the docker daemon's host (VM for colima/Docker Desktop,
    // the machine itself on Linux) — lets Ryuk mount it when DOCKER_HOST points to a user socket.
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    // webhook-signature integration test reads the secret from a real env var
    environment("TEST_HOOK_SECRET", "integration-test-hook-secret")
    testLogging {
        events("passed", "skipped", "failed")
    }
    retry {
        // One retry catches the rare random-port collision in @SpringBootTest;
        // maxFailures caps the safety net so a real regression still trips the
        // build (a broken commit reliably hits the failure ceiling on attempt 1).
        maxRetries.set(1)
        maxFailures.set(10)
        failOnPassedAfterRetry.set(false)
    }
}
