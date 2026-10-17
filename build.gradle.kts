plugins {
    java
    id("org.springframework.boot") version "4.1.1"
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
    // Boot 4 is modular: each technology's auto-configuration ships in its own starter, so spring-kafka and
    // flyway-core alone no longer switch anything on; their starters do.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    developmentOnly("com.h2database:h2") // bootRun only (never in the jar): the "loader" profile runs with an in-memory H2, ES the only real service
    // Pinned on purpose: Boot 4.1 manages the 9.x client, which is not supported against an 8.19 server (the Java
    // client is forward compatible only; docs/adr/0005). The 8.x client needs Jackson 2 for its JacksonJsonpMapper.
    implementation("co.elastic.clients:elasticsearch-java:8.18.8")
    implementation("org.elasticsearch.client:elasticsearch-rest-client:8.18.8") // Boot 4 no longer manages it; same version as the client
    implementation("com.fasterxml.jackson.core:jackson-databind") // Jackson 2 (Boot's jackson-2-bom), only for the ES client's JacksonJsonpMapper
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry") // tracing auto-config + the OTel bridge, no exporter
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")      // @AutoConfigureMockMvc
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")     // @JsonTest
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")       // spring-kafka-test, @EmbeddedKafka
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testRuntimeOnly("com.h2database:h2") // tests run without Postgres: H2 in PostgreSQL mode, same Flyway migrations
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.networknt:json-schema-validator:3.0.8") // validates every event envelope against src/main/resources/events/*.schema.json
}

tasks.withType<Test> {
    useJUnitPlatform()
    // ./gradlew test -Pes=true runs the live-Elasticsearch tests (@EnabledIfSystemProperty(named = "es")); off by default
    systemProperty("es", (findProperty("es") ?: "false").toString())
    testLogging { events("passed", "skipped", "failed") }
}
