# Gradle 9.8 build fixes

This checkpoint incorporates the first full external Gradle 9.8.0 build feedback for Step 3.

## Fixed issues

### 1. Fzzy Config registration

Incorrect:

```kotlin
ConfigApi.registerAndLoadConfig { GuardianConfig() }
```

`registerAndLoadConfig` takes the config factory as its first parameter and `RegisterType` as the final/defaulted parameter. Kotlin trailing-lambda syntax only targets the final parameter, so the old expression could not select either API overload.

Correct:

```kotlin
ConfigApi.registerAndLoadConfig(configClass = { GuardianConfig() })
```

### 2. Gradle 9.8 delegated project-property deprecations

The build no longer uses:

```kotlin
val name: String by project
```

All Gradle properties are obtained explicitly through `providers.gradleProperty(...)`.

### 3. Configuration-cache-safe resource processing

The old `processResources` actions referenced `project.version` and script-level values inside `filesMatching { ... }`. With configuration cache enabled, those execution-time actions could retain a reference to the Gradle `Project`/build script object.

Both modules now construct a plain `Map<String, String>` inside each task configuration and the execution-time copy action captures only that serializable map.

### 4. Wrapper target

The committed Gradle wrapper target is now **9.8.0**.

## Local verification

Use Java 21 and run:

```powershell
.\gradlew clean build --no-daemon --warning-mode all
```

Then run it a second time without `clean` to verify configuration-cache reuse:

```powershell
.\gradlew build --no-daemon --warning-mode all
```

A healthy second run should report configuration-cache reuse and should not report the earlier `Task.project` or script-object serialization problems.

The WorldEdit module can also be exercised directly:

```powershell
.\gradlew :worldedit-adapter:build --no-daemon --warning-mode all
```
