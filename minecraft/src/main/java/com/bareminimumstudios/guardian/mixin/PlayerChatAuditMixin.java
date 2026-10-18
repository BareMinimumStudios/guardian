package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.domain.PlayerActivityKind;
import com.bareminimumstudios.guardian.platform.minecraft.PlayerActivityCapture;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerChatAuditMixin {
    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V", at = @At("TAIL"))
    private void guardian$acceptedPublicChat(PlayerChatMessage message, Predicate<ServerPlayer> filter, ServerPlayer player, ChatType.Bound type, CallbackInfo ci) {
        if (player != null && type.chatType().is(ChatType.CHAT) && !message.isFullyFiltered()) {
            PlayerActivityCapture.capture(player, PlayerActivityKind.CHAT, message.decoratedContent().getString());
        }
    }
}
