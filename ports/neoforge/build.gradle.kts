plugins { kotlin("jvm");id("net.neoforged.moddev");id("dev.mixinmcp.decompile") }
val targetPins=rootProject.extra["guardianTargetPins"] as Map<*,*>
fun pin(key:String):String=targetPins[key] as? String ?: error("Missing target pin: $key")
val minecraftVersion=pin("minecraftVersion")
val targetJava=pin("javaVersion").toInt()

base { archivesName.set("guardian-neoforge") }
repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://maven.fzzyhmstrs.me/") { content { includeGroup("me.fzzyhmstrs") } }
    maven("https://thedarkcolour.github.io/KotlinForForge/") { content { includeGroup("thedarkcolour") } }
}
neoForge {
    version=pin("neoForgeVersion")
    runs { create("server") { server();gameDirectory.set(file(providers.gradleProperty("guardianTestRoot").orElse(layout.buildDirectory.dir("run-server").map { it.asFile.absolutePath }).get())) } }
    mods { create("guardian") { sourceSet(sourceSets.main.get()) } }
}
dependencies {
    implementation(project(":common"))
    implementation((targetPins["fzzyNeoForgeCoordinate"] as? String) ?: "me.fzzyhmstrs:fzzy_config:${pin("fzzyNeoForgeVersion")}") { exclude(group="dev.nyon",module="KotlinLangForge") }
    implementation("thedarkcolour:kotlinforforge-neoforge:${pin("kotlinForForgeVersion")}")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    jarJar("org.xerial:sqlite-jdbc") { version { strictly("[3.53.4.0]");prefer("3.53.4.0") } }
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.5")
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
    val props=mapOf("version" to project.version.toString(),"minecraftVersion" to minecraftVersion,"javaVersion" to targetJava.toString(),"javaCompatibility" to "JAVA_$targetJava","fzzyBaseVersion" to pin("fzzyNeoForgeVersion").substringBefore('+'),"kotlinLoaderVersion" to pin("kotlinForForgeVersion").substringBeforeLast('.'),"neoForgeVersion" to pin("neoForgeVersion"))
    filesMatching("*.mixins.json") { expand(props) }
    inputs.properties(props);filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
    from(baseline.resolve("src/main/resources/assets")) { into("assets") }
}
evaluationDependsOn(":common")
val commonOutput=project(":common").extensions.getByType<SourceSetContainer>().named("main").map { it.output }
tasks.jar { from(commonOutput) }

neoForge.mods.named("guardian") { sourceSet(project(":common").extensions.getByType<SourceSetContainer>().named("main").get()) }
fun Jar.excludeReplacedSources(base:File,patch:File) {
    exclude { entry -> entry.file.toPath().startsWith(base.toPath()) && patch.resolve(entry.relativePath.pathString).isFile }
}

dependencies { testImplementation(kotlin("test-junit5")) }
sourceSets.test { resources.srcDir(baseline.resolve("src/test/resources")) }
tasks.test { useJUnitPlatform() }

neoForge.unitTest { enable();testedMod.set(neoForge.mods.getByName("guardian")) }

dependencies { testImplementation("net.neoforged:testframework:${pin("neoForgeVersion")}") }
fun Test.isolatedWorkingDirectory(directory:File) {
    workingDir(directory)
    doFirst { directory.mkdirs() }
}
tasks.test { isolatedWorkingDirectory(layout.buildDirectory.dir("test-runtime").get().asFile) }

fun sourceRoots(language:String):List<File> = (
    listOf(baseline.resolve("minecraft/src/main/$language"),baseline.resolve("neoforge/src/main/$language"),modern.resolve(language))+
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
    duplicatesStrategy=DuplicatesStrategy.EXCLUDE
    for(language in listOf("java","kotlin")) {
        val roots=sourceRoots(language)
        roots.forEachIndexed { index,root ->
            roots.drop(index+1).forEach { replacement -> excludeReplacedSources(root,replacement) }
        }
    }
}


sourceSets.test { java.ordered(testRoots("java")) }
kotlin.sourceSets.test { kotlin.ordered(testRoots("kotlin")) }


// FML 9 keeps KFF in the plugin layer. Assertions must run in the game layer,
// not Gradle's parent loader, so Kotlin reflection and Result retain one identity.
if(pin("kotlinForForgeVersion").startsWith("5.")) {
    dependencies { add("additionalRuntimeClasspath","org.xerial:sqlite-jdbc:3.53.4.0") }
    val assertionLibraries=configurations.create("gameLayerAssertions") { isTransitive=false }
    dependencies {
        add(assertionLibraries.name,"org.jetbrains.kotlin:kotlin-test:2.4.20")
        add(assertionLibraries.name,"org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    }
    val assertionTrees=assertionLibraries.files.map { zipTree(it) }
    val assertionJar=tasks.register<Jar>("gameLayerAssertionsJar") {
        archiveFileName.set("guardian-test-assertions.jar")
        destinationDirectory.set(layout.buildDirectory.dir("test-support"))
        from(assertionTrees)
        exclude("module-info.class","META-INF/versions/**/module-info.class","META-INF/MANIFEST.MF")
        duplicatesStrategy=DuplicatesStrategy.EXCLUDE
        manifest.attributes("FMLModType" to "GAMELIBRARY","Automatic-Module-Name" to "guardian.test.assertions")
    }
    dependencies { testRuntimeOnly(files(assertionJar.flatMap { it.archiveFile })) }
    tasks.test {
        classpath=classpath.filter { !it.name.startsWith("kotlin-test-") }
        systemProperty("junit.jupiter.extensions.autodetection.enabled","true")
    }
}

sourceSets.test { resources.srcDirs(sourceVersions.map { project.file("src/$it/test/resources") }) }

// Older player inventories own armor/offhand lists directly; EntityEquipment does not exist.
val excludedMainSources=(targetPins["excludedMainSources"] as? String).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
fun org.gradle.api.file.SourceDirectorySet.excludePaths(paths:Set<String>) { exclude { it.relativePath.pathString in paths } }
fun Jar.excludePaths(paths:Set<String>) { exclude { it.relativePath.pathString in paths } }
sourceSets.main { java.excludePaths(excludedMainSources) }
tasks.named<Jar>("sourcesJar") { excludePaths(excludedMainSources) }
val mainResourceRoots=listOf(project.file("src/main/resources"))+sourceVersions.map { project.file("src/$it/resources") }
sourceSets.main { resources.setSrcDirs(emptyList<File>());resources.ordered(mainResourceRoots) }
