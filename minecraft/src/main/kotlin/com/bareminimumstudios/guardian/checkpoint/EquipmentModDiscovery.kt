package com.bareminimumstudios.guardian.checkpoint

/** Loader metadata only; optional equipment classes are loaded by their selected adapters. */
internal object EquipmentModDiscovery {
    fun installed():Map<String,String> {
        val loader=javaClass.classLoader
        val neo=try { Class.forName("net.neoforged.fml.ModList",false,loader) } catch(_:ClassNotFoundException) { null }
        if(neo!=null) {
            val list=checkNotNull(neo.getMethod("get").invoke(null))
            val mods=neo.getMethod("getMods").invoke(list) as Collection<*>
            val info=Class.forName("net.neoforged.neoforgespi.language.IModInfo",false,loader)
            val id=info.getMethod("getModId");val version=info.getMethod("getVersion")
            return mods.associate { id.invoke(it) as String to version.invoke(it).toString() }
        }
        val fabric=Class.forName("net.fabricmc.loader.api.FabricLoader",false,loader)
        val list=checkNotNull(fabric.getMethod("getInstance").invoke(null))
        val mods=fabric.getMethod("getAllMods").invoke(list) as Collection<*>
        val container=Class.forName("net.fabricmc.loader.api.ModContainer",false,loader).getMethod("getMetadata")
        val metadata=Class.forName("net.fabricmc.loader.api.metadata.ModMetadata",false,loader)
        val id=metadata.getMethod("getId");val version=metadata.getMethod("getVersion")
        val friendly=Class.forName("net.fabricmc.loader.api.Version",false,loader).getMethod("getFriendlyString")
        return mods.associate { mod ->
            val value=container.invoke(mod)
            id.invoke(value) as String to (friendly.invoke(version.invoke(value)) as String)
        }
    }
}
