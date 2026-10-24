package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.domain.PlayerActivityKind;
import com.bareminimumstudios.guardian.platform.minecraft.PlayerActivityCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records admission after parsing, not execution success. Earlier cancellation never reaches this hook. */
@Mixin(Commands.class)
public abstract class PlayerCommandAuditMixin {
    @WrapOperation(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/Commands;finishParsing(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;Lnet/minecraft/commands/CommandSourceStack;)Lcom/mojang/brigadier/context/ContextChain;"), require = 1)
    private ContextChain<CommandSourceStack> guardian$acceptedCommand(ParseResults<CommandSourceStack> parsed, String command, CommandSourceStack source, Operation<ContextChain<CommandSourceStack>> original) {
        ContextChain<CommandSourceStack> chain = original.call(parsed, command, source);
        if (chain != null && source.getPlayer() != null) {
            PlayerActivityCapture.capture(source.getPlayer(), PlayerActivityKind.COMMAND, command);
        }
        return chain;
    }
}
