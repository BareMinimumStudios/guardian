package com.bareminimumstudios.guardian.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayer.class)
public interface WhisperRecipientInvoker {
    @Invoker("acceptsChatMessages") boolean guardian$acceptsChatMessages();
}
