package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.ResourceId
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ClickEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Items

internal object CheckpointLinks {
    fun run(label:String,command:String):Component=Component.literal(label).withStyle {
        it.withColor(net.minecraft.ChatFormatting.AQUA).withClickEvent(ClickEvent.RunCommand(command))
    }
    fun icon(id:ResourceId?)=if(id?.namespace=="minecraft")BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id.toString())).orElse(Items.PAPER) else Items.PAPER
}
