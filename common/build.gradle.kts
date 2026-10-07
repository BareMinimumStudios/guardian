plugins {
    kotlin("jvm")
    `java-library`
}

group = providers.gradleProperty("mavenGroup").get()
version = providers.gradleProperty("modVersion").get()

repositories { mavenCentral() }

dependencies {
    implementation("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqliteJdbcVersion").get()}")
    implementation("org.duckdb:duckdb_jdbc:${providers.gradleProperty("duckdbJdbcVersion").get()}")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
tasks.test { useJUnitPlatform() }
java { withSourcesJar() }
