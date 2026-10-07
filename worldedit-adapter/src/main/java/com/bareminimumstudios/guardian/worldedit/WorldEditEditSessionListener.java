package com.bareminimumstudios.guardian.worldedit;

import com.bareminimumstudios.guardian.domain.ActorIdentity;
import com.bareminimumstudios.guardian.integration.api.GuardianIntegrationApi;
import com.bareminimumstudios.guardian.logging.bulk.BulkCaptureSession;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import net.minecraft.server.world.ServerWorld;

/** Registers exactly at WorldEdit's recommended block-logger stage: BEFORE_CHANGE. */
public final class WorldEditEditSessionListener {

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.getWorld() == null) {
            return;
        }
        if (!GuardianIntegrationApi.INSTANCE.worldEditLoggingEnabled()) {
            return;
        }

        Actor actor = event.getActor();
        String source = "worldedit:" + (actor == null ? "unknown" : actor.getName());
        BulkCaptureSession capture = GuardianIntegrationApi.INSTANCE.beginWorldEditCapture(source);
        if (capture == null) {
            event.setExtent(new RejectingAuditExtent(
                event.getExtent(),
                "Guardian refused this WorldEdit operation because no bulk audit capacity is available. " +
                    "Wait for pending edits to finish writing or increase the Guardian WorldEdit bulk limits."
            ));
            return;
        }

        try {
            net.minecraft.world.World nativeWorld = FabricAdapter.adapt(event.getWorld());
            if (!(nativeWorld instanceof ServerWorld serverWorld)) {
                capture.releaseUnused();
                event.setExtent(new RejectingAuditExtent(event.getExtent(), "Guardian could not resolve the WorldEdit server world."));
                return;
            }

            event.setExtent(new GuardianWorldEditExtent(
                event.getExtent(),
                serverWorld,
                actorIdentity(actor),
                capture
            ));
        } catch (Throwable t) {
            capture.releaseUnused();
            event.setExtent(new RejectingAuditExtent(
                event.getExtent(),
                "Guardian could not initialize WorldEdit audit capture: " + t.getMessage()
            ));
        }
    }

    private static ActorIdentity actorIdentity(Actor actor) {
        if (actor == null) {
            return new ActorIdentity.System("worldedit:unknown");
        }
        if (actor.isPlayer()) {
            return new ActorIdentity.Player(actor.getUniqueId(), actor.getName());
        }
        return new ActorIdentity.System("worldedit:" + actor.getName());
    }
}
