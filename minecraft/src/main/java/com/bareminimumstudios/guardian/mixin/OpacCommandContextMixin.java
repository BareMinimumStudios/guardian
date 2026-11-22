package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.OpacCommandHooks;
import com.bareminimumstudios.guardian.platform.minecraft.OpacStructureProtection;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Commands.class)
public abstract class OpacCommandContextMixin implements OpacCommandHooks {
    @WrapMethod(method="performCommand")
    private void guardian$command(ParseResults<CommandSourceStack> parsed,String text,Operation<Void> original) {
        OpacStructureProtection.withRequester(parsed.getContext().getSource().getPlayer(),()->original.call(parsed,text));
    }
}
