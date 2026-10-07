package com.bareminimumstudios.guardian.worldedit;

import com.bareminimumstudios.guardian.domain.ActionType;
import com.bareminimumstudios.guardian.domain.ActorIdentity;
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot;
import com.bareminimumstudios.guardian.domain.BlockPosition;
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot;
import com.bareminimumstudios.guardian.domain.ChangeCause;
import com.bareminimumstudios.guardian.domain.ResourceId;
import com.bareminimumstudios.guardian.logging.bulk.BulkCaptureSession;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftBlockRestorer;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftBlockSnapshotter;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * WorldEdit BEFORE_CHANGE extent. It captures exact native Minecraft snapshots only after WorldEdit reports that a
 * block mutation succeeded, while checking capacity before the mutation so an oversized edit fails closed.
 */
final class GuardianWorldEditExtent extends AbstractDelegateExtent {
    private final ServerWorld world;
    private final ActorIdentity actor;
    private final BulkCaptureSession capture;
    private final ResourceId dimension;
    private boolean committed;

    GuardianWorldEditExtent(Extent extent, ServerWorld world, ActorIdentity actor, BulkCaptureSession capture) {
        super(extent);
        this.world = world;
        this.actor = actor;
        this.capture = capture;
        this.dimension = ResourceId.Companion.parse(world.getRegistryKey().getValue().toString());
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws WorldEditException {
        if (!capture.hasCapacity()) {
            GuardianWorldEditException failure = new GuardianWorldEditException(
                "Guardian stopped this WorldEdit operation before an unlogged change could occur: " +
                    "the configured WorldEdit audit buffer limit was reached or the audit dispatcher became unavailable."
            );
            finishCapturedBestEffort(failure);
            throw failure;
        }

        BlockPos pos = new BlockPos(location.x(), location.y(), location.z());
        BlockPosition domainPos = new BlockPosition(location.x(), location.y(), location.z());
        final BlockStateSnapshot before;
        final boolean beforeAir;
        try {
            beforeAir = world.getBlockState(pos).isAir();
            before = MinecraftBlockSnapshotter.INSTANCE.snapshot(world, pos);
        } catch (Throwable t) {
            GuardianWorldEditException failure =
                new GuardianWorldEditException("Guardian could not snapshot a WorldEdit block before mutation.", t);
            finishCapturedBestEffort(failure);
            throw failure;
        }

        final boolean changed;
        try {
            changed = super.setBlock(location, block);
        } catch (WorldEditException e) {
            finishCapturedBestEffort(e);
            throw e;
        } catch (RuntimeException | Error e) {
            finishCapturedBestEffort(e);
            throw e;
        }
        if (!changed) {
            return false;
        }

        try {
            boolean afterAir = world.getBlockState(pos).isAir();
            BlockStateSnapshot after = MinecraftBlockSnapshotter.INSTANCE.snapshot(world, pos);
            if (before.equals(after)) {
                return true;
            }

            ActionType action = beforeAir && !afterAir
                ? ActionType.BLOCK_PLACE
                : (!beforeAir && afterAir ? ActionType.BLOCK_BREAK : ActionType.BLOCK_CHANGE);

            capture.record(new BlockChangeSnapshot(
                System.currentTimeMillis(),
                actor,
                dimension,
                domainPos,
                before,
                after,
                ChangeCause.WORLD_EDIT,
                action,
                UUID.randomUUID()
            ));
            return true;
        } catch (Throwable t) {
            // A changed-but-unlogged block is worse than aborting the edit. Compensate best-effort to the exact old state.
            MinecraftBlockRestorer.RestoreResult compensation =
                MinecraftBlockRestorer.INSTANCE.restore(world, domainPos, before);
            String suffix = compensation.getSuccess()
                ? " The changed block was restored to its pre-edit state."
                : " Compensation also failed: " + compensation.getReason();
            GuardianWorldEditException failure = new GuardianWorldEditException(
                "Guardian failed to capture the resulting WorldEdit block state." + suffix,
                t
            );
            finishCapturedBestEffort(failure);
            throw failure;
        }
    }

    private void finishCapturedBestEffort(Throwable originalFailure) {
        try {
            capture.commit();
        } catch (Throwable commitFailure) {
            originalFailure.addSuppressed(commitFailure);
        }
    }

    @Override
    protected com.sk89q.worldedit.function.operation.Operation commitBefore() {
        if (!committed) {
            committed = true;
            capture.commit();
        }
        return null;
    }
}
