import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    application
    // Builds the fat (uber) jar: ./gradlew shadowJar. `com.gradleup.shadow` is the
    // maintained fork of the (now-unmaintained) johnrengelman plugin; it is the one
    // that works on Gradle 8.3+ / 9.x.
    id("com.gradleup.shadow") version "9.0.0"
    // Test-coverage measurement (koverXmlReport / koverHtmlReport / koverVerify).
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "com.lifted"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Web framework
    implementation("io.javalin:javalin:5.6.3")

    // JSON — Jackson for Javalin request/response, Gson for gateway/EasyPost payloads
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.google.code.gson:gson:2.11.0")

    // Shipping — multi-carrier labels, rates, tracking
    implementation("com.easypost:easypost-api-client:7.0.0")

    // HTTP client for the Lifted Payments gateway and Twilio Verify
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // Optional PostgreSQL label store — driver + HikariCP pool, loaded only when
    // SHIPKIT_STORE=postgres. Matches Lifted's managed Postgres — Postgres-only, no other RDBMS.
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Kotlin stdlib
    implementation(kotlin("stdlib"))

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Mocking — MockK for the shipping/payments client seams (mocks final classes).
    testImplementation("io.mockk:mockk:1.13.13")

    // In-process HTTP integration tests against the real Javalin app.
    testImplementation("io.javalin:javalin-testtools:5.6.3")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // Testcontainers — spins up a real postgres:16 to exercise the Postgres stores.
    // The tests skip gracefully (Assumptions) when no Docker daemon is available.
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

application {
    mainClass.set("com.lifted.shipkit.AppKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
    // MockK's inline mock maker + Testcontainers both want a roomy heap.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// ---- Coverage (Kover) -------------------------------------------------------
// `./gradlew koverXmlReport`  → build/reports/kover/report.xml   (CI + badges)
// `./gradlew koverHtmlReport` → build/reports/kover/html/index.html
// `./gradlew koverVerify`     → fails the build under the core-coverage floor.
kover {
    reports {
        total {
            xml { onCheck.set(true) }
            html { onCheck.set(true) }
            verify {
                rule {
                    // Core business logic must stay well-covered (CONTRACTS §8: ≥80%).
                    // The full suite sits at ~90% line coverage WITH Docker present.
                    minBound(80)
                }
            }
        }
        // The two Postgres integration classes are exercised by the Testcontainers
        // suite when a Docker daemon is available and skipped gracefully otherwise.
        // Excluding them from the coverage metric keeps BOTH the reported number and
        // the enforced floor deterministic regardless of Docker; every other class —
        // including the in-memory stores that mirror the same behaviour — is measured.
        filters {
            excludes {
                classes(
                    "com.lifted.shipkit.store.PostgresLabelStore",
                    "com.lifted.shipkit.store.PostgresLabelStoreKt",
                    "com.lifted.shipkit.security.PostgresApiKeyStore",
                )
            }
        }
    }
}

tasks.shadowJar {
    archiveBaseName.set("shipkit")
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.lifted.shipkit.AppKt"
    }
}

// Make `./gradlew build` also produce the fat jar.
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Mint a ShipKit API key from the command line. Reads the same environment the
// server does (so it writes to the configured memory/postgres store), then prints
// the full key ONCE.
//   ./gradlew shipkitKeygen -Plabel=prod-checkout          # a sk_live_ key
//   ./gradlew shipkitKeygen -Plabel=local-dev -Ptest       # a sk_test_ key
tasks.register<JavaExec>("shipkitKeygen") {
    group = "shipkit"
    description = "Mint a ShipKit API key (-Plabel=<name> [-Ptest] [-Ppublishable]); prints the key once."
    mainClass.set("com.lifted.shipkit.KeygenKt")
    classpath = sourceSets["main"].runtimeClasspath
    // JavaExec inherits the current process environment, so SHIPKIT_STORE /
    // SHIPKIT_DATABASE_URL etc. flow through to ShipKitConfig.fromEnv().
    val labelArg = (project.findProperty("label") as? String)?.let { "--label=$it" }
    args = listOfNotNull(labelArg, if (project.hasProperty("test")) "--test" else null, if (project.hasProperty("publishable")) "--publishable" else null)
}
