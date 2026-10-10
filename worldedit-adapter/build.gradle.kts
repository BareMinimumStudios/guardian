plugins {
    id("dev.mixinmcp.decompile")
    id("fabric-loom") version "1.17.21"
    kotlin("jvm") version "2.4.20"
    `maven-publish`
}

val minecraftVersion = providers.gradleProperty("minecraftVersion").get()
val loaderVersion = providers.gradleProperty("loaderVersion").get()
val fabricApiVersion = providers.gradleProperty("fabricApiVersion").get()
val fabricKotlinVersion = providers.gradleProperty("fabricKotlinVersion").get()
val worldeditVersion = providers.gradleProperty("worldeditVersion").get()
val modVersion = providers.gradleProperty("modVersion").get()
val mavenGroup = providers.gradleProperty("mavenGroup").get()

group = mavenGroup
version = modVersion

base {
    archivesName.set("guardian-worldedit")
}

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/") { name = "EngineHub" }
}

dependencies {
    implementation(project(":common"))
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // Compile against the BML core without bundling it into the adapter artifact.
    implementation(project(path = ":", configuration = "namedElements"))

    // WorldEdit 7.3.8 is the 1.21/1.21.1 line used by this adapter. It remains a runtime dependency.
    modCompileOnly("com.sk89q.worldedit:worldedit-fabric-mc1.21:$worldeditVersion")

    testImplementation(kotlin("test-junit5"))
}

tasks.processResources {
    // Capture plain values in the task configuration so the copy action never reaches back
    // into the Project/build-script object when configuration cache is enabled.
    val resourceProperties: Map<String, String> = mapOf(
        "version" to modVersion,
        "worldeditVersion" to worldeditVersion
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

tasks.test { useJUnitPlatform() }

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "guardian-worldedit"
            from(components["java"])
        }
    }
}

tasks.jar { from(file("LICENSE")) }
