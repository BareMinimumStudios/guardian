package com.bareminimumstudios.guardian.mixin;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SimpleRegionStorage.class)
public interface ChunkStorageAccessor { @Accessor("worker") IOWorker guardian$worker(); }
