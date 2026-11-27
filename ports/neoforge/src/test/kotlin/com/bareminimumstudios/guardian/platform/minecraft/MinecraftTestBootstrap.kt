package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.core.HolderLookup
import net.neoforged.testframework.junit.EphemeralTestServerProvider

/** Real data-pack loading binds validated components; the ephemeral server has no gameplay world. */
object MinecraftTestBootstrap {
    val registries: HolderLookup.Provider = EphemeralTestServerProvider.grabServer().registryAccess()
    fun initialize() { /* Shared singleton test resource context; no production files are opened. */ }
}
