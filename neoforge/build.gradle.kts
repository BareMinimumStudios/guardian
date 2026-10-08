plugins {
    id("dev.mixinmcp.decompile")
    kotlin("jvm")
    id("net.neoforged.moddev") version "2.0.148"
}

val bundleDuckDb = providers.gradleProperty("bundleDuckDb").map { it.toBooleanStrict() }.getOrElse(false)

group = providers.gradleProperty("mavenGroup").get()
version = providers.gradleProperty("modVersion").get()
base { archivesName.set(if (bundleDuckDb) "guardian-neoforge-with-duckdb" else "guardian-neoforge") }

repositories {
    mavenCentral()
    maven("https://maven.fzzyhmstrs.me/") { content { includeGroup("me.fzzyhmstrs") } }
    maven("https://thedarkcolour.github.io/KotlinForForge/") { content { includeGroup("thedarkcolour") } }
}

neoForge {
    enable {
        version = "21.1.256"
        setDisableRecompilation(true)
    }
    runs { create("server") { server() } }
    mods { create("guardian") { sourceSet(sourceSets.main.get()) } }
}

dependencies {
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.5")
    implementation(project(":common"))
    implementation("me.fzzyhmstrs:fzzy_config:0.7.6+1.21+neoforge")
    implementation("thedarkcolour:kotlinforforge-neoforge:5.12.0")
    implementation("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqliteJdbcVersion").get()}")
    jarJar("org.xerial:sqlite-jdbc") { version { strictly("[3.53.4.0]"); prefer("3.53.4.0") } }
    if (bundleDuckDb) {
        implementation("org.duckdb:duckdb_jdbc:${providers.gradleProperty("duckdbJdbcVersion").get()}")
        jarJar("org.duckdb:duckdb_jdbc") { version { strictly("[1.4.5.0]"); prefer("1.4.5.0") } }
    }
}

sourceSets.main {
    java.srcDir(rootProject.file("minecraft/src/main/java"))

}
kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir(rootProject.file("minecraft/src/main/kotlin")) }
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
tasks.processResources {
    val props = mapOf("version" to providers.gradleProperty("modVersion").get())
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
    from(rootProject.file("src/main/resources/assets")) { into("assets") }
}

evaluationDependsOn(":common")
val commonOutput = project(":common").extensions.getByType<SourceSetContainer>().named("main").map { it.output }
tasks.jar { from(commonOutput); from(rootProject.file("LICENSE")) }
java { withSourcesJar() }
tasks.named<Jar>("sourcesJar") { from(rootProject.file("common/src/main/kotlin")) }
