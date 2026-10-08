package com.bareminimumstudios.guardian.mixin;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("storage") RegionFileStorage guardian$storage();
    @Invoker("submitTask") CompletableFuture<Optional<CompoundTag>> guardian$readTask(Supplier<Either<Optional<CompoundTag>,Exception>> task);
}
