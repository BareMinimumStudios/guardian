package com.bareminimumstudios.guardian.config

import com.bareminimumstudios.guardian.Guardian
import com.bareminimumstudios.guardian.storage.StorageBackendType
import me.fzzyhmstrs.fzzy_config.annotations.Action
import me.fzzyhmstrs.fzzy_config.annotations.AdminAccess
import me.fzzyhmstrs.fzzy_config.annotations.RequiresAction
import me.fzzyhmstrs.fzzy_config.annotations.Version
import me.fzzyhmstrs.fzzy_config.annotations.WithCustomPerms
import me.fzzyhmstrs.fzzy_config.config.Config
import me.fzzyhmstrs.fzzy_config.config.ConfigSection
import me.fzzyhmstrs.fzzy_config.validation.misc.ValidatedBoolean
import me.fzzyhmstrs.fzzy_config.validation.misc.ValidatedEnum
import me.fzzyhmstrs.fzzy_config.validation.misc.ValidatedString
import me.fzzyhmstrs.fzzy_config.validation.number.ValidatedInt
import net.minecraft.util.Identifier

@Version(4)
@AdminAccess(perms = ["guardian.config.admin"], fallback = 4)
@WithCustomPerms(perms = ["guardian.config.edit"], fallback = 3)
class GuardianConfig : Config(Identifier.of(Guardian.MOD_ID, "main")) {

    var general = General()
    class General : ConfigSection() {
        @RequiresAction(Action.RESTART)
        var enabled = ValidatedBoolean(true)

        @RequiresAction(Action.RESTART)
        var verboseStartup = ValidatedBoolean(false)
    }

    var logging = Logging()
    class Logging : ConfigSection() {
        /** Live master switch for capture. Storage/history remain available while disabled. */
        var enabled = ValidatedBoolean(true)

        /** Player-originated primary block placement and break capture (Step 2B). */
        var playerBlockChanges = ValidatedBoolean(true)
    }

    var storage = Storage()
    class Storage : ConfigSection() {
        @RequiresAction(Action.RESTART)
        var backend = ValidatedEnum(StorageBackendType.SQLITE)

        @RequiresAction(Action.RESTART)
        var sqliteFile = ValidatedString("guardian.sqlite", "[A-Za-z0-9._-]+")

        @RequiresAction(Action.RESTART)
        var duckDbFile = ValidatedString("guardian.duckdb", "[A-Za-z0-9._-]+")

        var warnWhenNonPersistent = ValidatedBoolean(true)
    }

    var performance = Performance()
    class Performance : ConfigSection() {
        @RequiresAction(Action.RESTART)
        var queueCapacity = ValidatedInt(16_384, 1_000_000, 1_024)

        @RequiresAction(Action.RESTART)
        var batchSize = ValidatedInt(256, 8_192, 1)

        @RequiresAction(Action.RESTART)
        var flushIntervalMillis = ValidatedInt(1_000, 60_000, 50)
    }

    var lookup = Lookup()
    class Lookup : ConfigSection() {
        var defaultResults = ValidatedInt(10, 100, 1)
        var maxResults = ValidatedInt(500, 10_000, 1)
        var inspectorResults = ValidatedInt(10, 100, 1)
        var maxRadius = ValidatedInt(100, 10_000, 0)
    }

    var rollback = Rollback()
    class Rollback : ConfigSection() {
        var defaultRadius = ValidatedInt(10, 100, 0)
        var maxRadius = ValidatedInt(100, 10_000, 0)
        var maxRecords = ValidatedInt(5_000, 9_999, 1)
        var blocksPerTick = ValidatedInt(100, 2_000, 1)
    }

    var integrations = Integrations()
    class Integrations : ConfigSection() {
        var detectWorldEdit = ValidatedBoolean(true)

        /** WorldEdit edit-session logging through the optional guardian-worldedit adapter. */
        var worldEditLogging = ValidatedBoolean(true)

        /** Enables r:#worldedit and r:#we cuboid selection filters when the adapter is installed. */
        var worldEditSelections = ValidatedBoolean(true)

        @RequiresAction(Action.RESTART)
        var worldEditMaxChangesPerOperation = ValidatedInt(100_000, 1_000_000, 1_000)

        @RequiresAction(Action.RESTART)
        var worldEditMaxPendingOperations = ValidatedInt(2, 16, 1)

        @RequiresAction(Action.RESTART)
        var usePermissionApi = ValidatedBoolean(true)
    }
}
