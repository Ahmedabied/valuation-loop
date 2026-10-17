plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "uk.valuationloop"
version = "0.1.0-SNAPSHOT"
description = "Valuation Loop: the backend for the choose-an-agent moment (valuation request -> delivery -> evidenced reply -> outcome)"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    developmentOnly("com.h2database:h2") // bootRun only (never in the jar): the "loader" profile runs with an in-memory H2, ES the only real service
    implementation("co.elastic.clients:elasticsearch-java:8.18.8")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("com.h2database:h2") // tests run without Postgres: H2 in PostgreSQL mode, same Flyway migrations
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.networknt:json-schema-validator:1.5.9") // validates every event envelope against src/main/resources/events/*.schema.json
}

tasks.withType<Test> {
    useJUnitPlatform()
    // ./gradlew test -Pes=true runs the live-Elasticsearch tests (@EnabledIfSystemProperty(named = "es")); off by default
    systemProperty("es", (findProperty("es") ?: "false").toString())
    testLogging { events("passed", "skipped", "failed") }
}
