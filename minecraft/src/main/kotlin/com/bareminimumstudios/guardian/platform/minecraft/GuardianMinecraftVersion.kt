package com.bareminimumstudios.guardian.platform.minecraft

/** The exact target is packaged by Gradle, including for maintenance before a server exists. */
object GuardianMinecraftVersion {
    val current: String by lazy {
        val properties=java.util.Properties()
        checkNotNull(GuardianMinecraftVersion::class.java.getResourceAsStream("/guardian-build.properties")) {
            "Guardian build metadata is missing"
        }.use { properties.load(it) }
        val version=checkNotNull(properties.getProperty("minecraftVersion"))
        check(version.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}"))) { "Guardian Minecraft target is invalid" }
        version
    }
}
