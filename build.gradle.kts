plugins {
    id("fabric-loom") version "1.17.21"
    kotlin("jvm") version "2.4.20"
    id("dev.mixinmcp.decompile") version "1.5.0"
    `maven-publish`
}

val minecraftVersion = providers.gradleProperty("minecraftVersion").get()
val loaderVersion = providers.gradleProperty("loaderVersion").get()
val fabricApiVersion = providers.gradleProperty("fabricApiVersion").get()
val fabricKotlinVersion = providers.gradleProperty("fabricKotlinVersion").get()
val fzzyConfigVersion = providers.gradleProperty("fzzyConfigVersion").get()
val fabricPermissionsVersion = providers.gradleProperty("fabricPermissionsVersion").get()
val sqliteJdbcVersion = providers.gradleProperty("sqliteJdbcVersion").get()
val bundleDuckDb = providers.gradleProperty("bundleDuckDb").map { it.toBooleanStrict() }.getOrElse(false)
val duckdbJdbcVersion = providers.gradleProperty("duckdbJdbcVersion").get()
val modVersion = providers.gradleProperty("modVersion").get()
val mavenGroup = providers.gradleProperty("mavenGroup").get()
val archivesBaseName = providers.gradleProperty("archivesBaseName").get()

group = mavenGroup
version = modVersion

base {
    archivesName.set(if (bundleDuckDb) "$archivesBaseName-with-duckdb" else archivesBaseName)
}

repositories {
    mavenCentral()
    maven("https://maven.fzzyhmstrs.me/") {
        name = "FzzyMaven"
        content { includeGroup("me.fzzyhmstrs") }
    }
}

dependencies {
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.5")
    implementation(project(":common"))
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // External dependency by design. Fzzy Config's license forbids jar-in-jar/include bundling.
    modImplementation("me.fzzyhmstrs:fzzy_config:$fzzyConfigVersion")

    // 0.3.1 is the correct Fabric Permissions API line for Minecraft 1.21.1.
    // Compile-only: Guardian detects the API at runtime and falls back to vanilla op levels when absent.
    modCompileOnly("me.lucko:fabric-permissions-api:$fabricPermissionsVersion")

    // SQLite is self-contained; optional builds can retain DuckDB without a separate add-on.
    implementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    include("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    if (bundleDuckDb) {
        implementation("org.duckdb:duckdb_jdbc:$duckdbJdbcVersion")
        include("org.duckdb:duckdb_jdbc:$duckdbJdbcVersion")
    }

    testImplementation(kotlin("test-junit5"))
}

tasks.processResources {
    // Keep only plain, serializable values inside the execution-time copy action.
    // This is required by Gradle 9.8's configuration cache.
    val resourceProperties: Map<String, String> = mapOf(
        "version" to modVersion,
        "loaderVersion" to loaderVersion,
        "fabricKotlinVersion" to fabricKotlinVersion,
        "fzzyConfigVersion" to fzzyConfigVersion
    )
    inputs.properties(resourceProperties)

    filesMatching("fabric.mod.json") {
        expand(resourceProperties)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = archivesBaseName
            from(components["java"])
        }
    }
}

// Bundle the platform-neutral core directly into the Fabric artifact.
evaluationDependsOn(":common")
val commonOutput = project(":common").extensions.getByType<SourceSetContainer>().named("main").map { it.output }
tasks.jar { from(commonOutput) }
tasks.named<Jar>("sourcesJar") { from(project(":common").file("src/main/kotlin")) }





sourceSets.main { java.srcDir("minecraft/src/main/java") }
kotlin.sourceSets.main { kotlin.srcDir("minecraft/src/main/kotlin") }
