package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.core.HolderLookup
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries

/** Vanilla data-generation lookup binds real default components, without a world or production database. */
object MinecraftTestBootstrap {
    val registries: HolderLookup.Provider
    init {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        registries=VanillaRegistries.createLookup()
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach { it.apply() }
    }
    fun initialize() { /* Object initialization establishes the registry/component context once. */ }
}
