package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.data.registries.VanillaRegistries

/** Vanilla data-generation lookup binds real default components, without a world or production database. */
object MinecraftTestBootstrap {
    val registries: net.minecraft.core.HolderLookup.Provider
    init {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        registries=VanillaRegistries.createLookup()
    }
    fun initialize() { /* Object initialization establishes the registry/component context once. */ }
}
