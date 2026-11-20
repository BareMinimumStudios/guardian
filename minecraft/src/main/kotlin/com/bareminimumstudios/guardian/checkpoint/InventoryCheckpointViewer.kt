package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider

internal object InventoryCheckpointViewer {
    fun open(player:ServerPlayer,value:InventoryCheckpoint,permitted:()->Boolean,preview:()->Unit) {
        fun allowed()=runCatching { permitted() && !player.isRemoved && MinecraftInventoryCoordination.allowsPlayerMutation(player) }.getOrDefault(false)
        if(!allowed() || player.isSpectator || player.containerMenu!==player.inventoryMenu && player.containerMenu !is InventoryCheckpointMenu || !player.containerMenu.carried.isEmpty) {
            player.sendSystemMessage(Component.literal("Guardian: close your menu and clear your cursor before viewing inventory checkpoints. Spectator and protected inventories cannot use this viewer."));return
        }
        player.openMenu(SimpleMenuProvider({ id,_,_ -> InventoryCheckpointMenu(id,player,value,::allowed,preview) },Component.literal("Guardian inventory checkpoint")))
    }
}
