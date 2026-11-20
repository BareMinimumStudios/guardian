plugins {
    kotlin("jvm")
    `java-library`
}

group = providers.gradleProperty("mavenGroup").get()
version = rootProject.version

repositories { mavenCentral() }
val targetPins=rootProject.extra["guardianTargetPins"] as Map<*,*>
val slf4jVersion=targetPins["slf4jVersion"] as? String ?: "2.0.17"

dependencies {
    implementation("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqliteJdbcVersion").get()}")
    testRuntimeOnly("org.duckdb:duckdb_jdbc:${providers.gradleProperty("duckdbJdbcVersion").get()}")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
tasks.test { useJUnitPlatform() }
java { withSourcesJar() }

fun org.gradle.api.file.SourceDirectorySet.overlay(base:File,patch:File) {
    srcDir(base);srcDir(patch)
    exclude { entry -> entry.file.toPath().startsWith(base.toPath()) && patch.resolve(entry.relativePath.pathString).isFile }
}
kotlin.sourceSets.main { kotlin.overlay(rootProject.projectDir.parentFile.resolve("common/src/main/kotlin"),project.file("src/main/kotlin")) }
kotlin.sourceSets.test { kotlin.srcDir(rootProject.projectDir.parentFile.resolve("common/src/test/kotlin")) }

fun Jar.excludeReplacedSources(base:File,patch:File) {
    exclude { entry -> entry.file.toPath().startsWith(base.toPath()) && patch.resolve(entry.relativePath.pathString).isFile }
}
tasks.named<Jar>("sourcesJar") {
    excludeReplacedSources(rootProject.projectDir.parentFile.resolve("common/src/main/kotlin"),project.file("src/main/kotlin"))
}

sourceSets.test { java.srcDir(rootProject.projectDir.parentFile.resolve("common/src/test/java"));resources.srcDir(rootProject.projectDir.parentFile.resolve("common/src/test/resources")) }
