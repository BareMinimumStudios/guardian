package com.bareminimumstudios.guardian.config

import me.fzzyhmstrs.fzzy_config.api.ConfigApi

object GuardianConfigManager {
    val config: GuardianConfig by lazy {
        ConfigApi.registerAndLoadConfig(configClass = { GuardianConfig() })
    }

    fun initialize() {
        config
    }
}
