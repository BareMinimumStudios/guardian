package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerActivityCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MsgCommand.class)
public abstract class WhisperAuditMixin {
    @WrapOperation(method="sendMessage",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V"))
    private static void guardian$whisper(ServerPlayer recipient,OutgoingChatMessage outgoing,boolean filtered,ChatType.Bound type,Operation<Void> original,
        CommandSourceStack source,Collection<ServerPlayer> targets,PlayerChatMessage message) {
        boolean accepts=((WhisperRecipientInvoker)recipient).guardian$acceptsChatMessages();
        original.call(recipient,outgoing,filtered,type);
        if(accepts && source.getPlayer()!=null)PlayerActivityCapture.whisper(source.getPlayer(),recipient,message,filtered);
    }
}
