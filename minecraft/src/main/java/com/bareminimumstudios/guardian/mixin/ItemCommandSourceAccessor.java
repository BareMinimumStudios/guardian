package com.bareminimumstudios.guardian.mixin;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(CommandSourceStack.class)
public interface ItemCommandSourceAccessor {
    @Accessor("source") CommandSource guardian$itemCommandSource();
}
