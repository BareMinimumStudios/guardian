package com.bareminimumstudios.guardian.worldedit;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.util.formatting.text.TextComponent;

final class GuardianWorldEditException extends WorldEditException {
    GuardianWorldEditException(String message) {
        super(TextComponent.of(message));
    }

    GuardianWorldEditException(String message, Throwable cause) {
        super(TextComponent.of(message), cause);
    }
}
