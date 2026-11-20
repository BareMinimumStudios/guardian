package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.permission.PermissionService
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider

/** DB work uses the existing history worker; authority and menu identity are checked on completion. */
object ItemTransactionViewer {
    fun link(command: String): Component=Component.literal("[Open item UI]").withStyle {
        it.withColor(net.minecraft.ChatFormatting.AQUA).withClickEvent(net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,command))
    }
    fun open(player: ServerPlayer,query: ContainerLookupQuery,history: BlockHistoryService,permissions: PermissionService,available: () -> Boolean) {
        fun allowed()=runCatching {
            available() && !player.isRemoved && player.server.playerList.getPlayer(player.uuid)===player &&
                permissions.has(player.createCommandSourceStack(),"guardian.lookup",2) && MinecraftInventoryCoordination.allowsPlayerMutation(player)
        }.getOrDefault(false)
        if(!allowed()) {
            player.sendSystemMessage(Component.literal("Guardian item UI unavailable: check lookup permission and whether inventory recovery is active."));return
        }
        if(player.isSpectator) { player.sendSystemMessage(Component.literal("Guardian item UI requires a non-spectator player for menu navigation."));return }
        if(player.containerMenu!==player.inventoryMenu && player.containerMenu !is ItemTransactionMenu || !player.containerMenu.carried.isEmpty) {
            player.sendSystemMessage(Component.literal("Guardian: close your current menu and clear your cursor before opening item history."));return
        }
        var current=query
        lateinit var menu: ItemTransactionMenu
        fun load() {
            if(player.containerMenu!==menu || !menu.stillValid(player))return
            menu.loading(current.offset/current.limit+1)
            val requested=current
            history.lookupContainers(requested,{ rows ->
                if(player.containerMenu===menu && menu.stillValid(player) && current===requested) {
                    runCatching { menu.results(rows,requested.offset/requested.limit+1,requested.offset>0,rows.size==requested.limit && requested.offset/requested.limit<9999) }
                        .onFailure { player.closeContainer();player.sendSystemMessage(Component.literal("Guardian item UI: ${it.message}")) }
                }
            },{ if(player.containerMenu===menu && menu.stillValid(player)) { player.closeContainer();player.sendSystemMessage(Component.literal("Guardian item UI lookup failed; see server log.")) } })
        }
        val opened=player.openMenu(SimpleMenuProvider({ id,_,_ ->
            ItemTransactionMenu(id,player,::allowed) { direction ->
                current=current.copy(offset=(current.offset+direction*current.limit).coerceAtLeast(0));load()
            }.also { menu=it }
        },Component.literal("Guardian item history")))
        if(opened.isPresent && player.containerMenu===menu)load()
    }
}
