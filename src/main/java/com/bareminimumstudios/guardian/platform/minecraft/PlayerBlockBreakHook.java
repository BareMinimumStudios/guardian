package com.bareminimumstudios.guardian.platform.minecraft;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

/** Java/Fabric event glue kept out of the platform-neutral capture logic. */
public final class PlayerBlockBreakHook {
    private PlayerBlockBreakHook() {
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register(PlayerBlockCapture::captureBreak);
    }
}
