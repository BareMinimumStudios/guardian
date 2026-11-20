plugins { kotlin("jvm");id("dev.mixinmcp.decompile") }
val targetPins=rootProject.extra["guardianTargetPins"] as Map<*,*>
fun pin(key:String):String=targetPins[key] as? String ?: error("Missing target pin: $key")
val minecraftVersion=pin("minecraftVersion")
val targetJava=pin("javaVersion").toInt()
val remapped=targetPins["mappingMode"]=="mojang"
apply(plugin=if(remapped)"net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")
val loom=extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>()
val modDependency=if(remapped)"modImplementation" else "implementation"

base { archivesName.set("guardian") }
repositories {
    mavenCentral()
    maven("https://maven.fzzyhmstrs.me/") { content { includeGroup("me.fzzyhmstrs") } }
}
dependencies {
    add("minecraft","com.mojang:minecraft:${minecraftVersion}")
    if(remapped)add("mappings",loom.officialMojangMappings())
    implementation(project(":common"))
    add(modDependency,"net.fabricmc:fabric-loader:${pin("loaderVersion")}")
    add(modDependency,"net.fabricmc.fabric-api:fabric-api:${pin("fabricApiVersion")}")
    add(modDependency,"net.fabricmc:fabric-language-kotlin:${pin("fabricKotlinVersion")}")
    add(modDependency,"me.fzzyhmstrs:fzzy_config:${pin("fzzyFabricVersion")}") {
        // Mod Menu is a client integration, outside the dedicated-server runtime.
        exclude(group="com.terraformersmc",module="modmenu")
    }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    add("include","org.xerial:sqlite-jdbc:3.53.4.0")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.5")
    if(remapped)add("modCompileOnly","me.lucko:fabric-permissions-api:${pin("permissionsApiVersion")}")
}
// A version-specific file replaces the matching baseline source without copying unrelated hooks.
val baseline=rootProject.projectDir.parentFile
val modern=rootProject.file("minecraft/26.2")
val sourceVersions=(rootProject.extra["guardianSourceVersions"] as List<*>).map { it as String }
kotlin {
    jvmToolchain(targetJava)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(targetJava.toString())) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(targetJava);options.encoding="UTF-8" }
tasks.withType<Jar>().configureEach { from(baseline.resolve("LICENSE"));isPreserveFileTimestamps=false;isReproducibleFileOrder=true }
java { withSourcesJar() }

tasks.processResources {
    val props=mapOf("version" to project.version.toString(),"minecraftVersion" to minecraftVersion,"javaVersion" to targetJava.toString(),"javaCompatibility" to "JAVA_$targetJava","fzzyBaseVersion" to pin("fzzyFabricVersion").substringBefore('+'),"kotlinLoaderVersion" to pin("kotlinForForgeVersion").substringBeforeLast('.'),"neoForgeVersion" to "")
    filesMatching("*.mixins.json") { expand(props) }
    inputs.properties(props);filesMatching("fabric.mod.json") { expand(props) }
    from(baseline.resolve("src/main/resources/assets")) { into("assets") }
}
evaluationDependsOn(":common")
val commonOutput=project(":common").extensions.getByType<SourceSetContainer>().named("main").map { it.output }
tasks.jar { from(commonOutput) }

loom.apply { runs { named("server") { runDir(providers.gradleProperty("guardianTestRoot").orElse(layout.buildDirectory.dir("run-server").map { it.asFile.absolutePath }).get()) } } }

fun Jar.excludeReplacedSources(base:File,patch:File) {
    exclude { entry -> entry.file.toPath().startsWith(base.toPath()) && patch.resolve(entry.relativePath.pathString).isFile }
}

dependencies { testImplementation(kotlin("test-junit5")) }
sourceSets.test { resources.srcDir(baseline.resolve("src/test/resources")) }
tasks.test { useJUnitPlatform() }

fun sourceRoots(language:String):List<File> = (
    listOf(baseline.resolve("minecraft/src/main/$language"),baseline.resolve("src/main/$language"),modern.resolve(language))+
    sourceVersions.map { rootProject.file("minecraft/$it/$language") }+
    listOf(project.file("src/main/$language"))+
    sourceVersions.map { project.file("src/$it/$language") }
).distinct()
fun testRoots(language:String):List<File> = (
    listOf(baseline.resolve("src/test/$language"),modern.resolve("test/$language"))+
    sourceVersions.map { rootProject.file("minecraft/$it/test/$language") }+
    listOf(project.file("src/test/$language"))+
    sourceVersions.map { project.file("src/$it/test/$language") }
).distinct()
fun org.gradle.api.file.SourceDirectorySet.ordered(roots:List<File>) {
    roots.forEach { srcDir(it) }
    exclude { entry ->
        roots.withIndex().any { (index,root) ->
            entry.file.toPath().startsWith(root.toPath()) &&
                roots.drop(index+1).any { it.resolve(entry.relativePath.pathString).isFile }
        }
    }
}
sourceSets.main { java.ordered(sourceRoots("java")) }
kotlin.sourceSets.main { kotlin.ordered(sourceRoots("kotlin")) }
tasks.named<Jar>("sourcesJar") {

    for(language in listOf("java","kotlin")) {
        val roots=sourceRoots(language)
        roots.forEachIndexed { index,root ->
            roots.drop(index+1).forEach { replacement -> excludeReplacedSources(root,replacement) }
        }
    }
}

sourceSets.test { java.ordered(testRoots("java")) }
kotlin.sourceSets.test { kotlin.ordered(testRoots("kotlin")) }

// Older player inventories own armor/offhand lists directly; EntityEquipment does not exist.
val excludedMainSources=(targetPins["excludedMainSources"] as? String).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
fun org.gradle.api.file.SourceDirectorySet.excludePaths(paths:Set<String>) { exclude { it.relativePath.pathString in paths } }
fun Jar.excludePaths(paths:Set<String>) { exclude { it.relativePath.pathString in paths } }
sourceSets.main { java.excludePaths(excludedMainSources) }
tasks.named<Jar>("sourcesJar") { excludePaths(excludedMainSources) }
val mainResourceRoots=listOf(project.file("src/main/resources"))+sourceVersions.map { project.file("src/$it/resources") }
sourceSets.main { resources.setSrcDirs(emptyList<File>());resources.ordered(mainResourceRoots) }
