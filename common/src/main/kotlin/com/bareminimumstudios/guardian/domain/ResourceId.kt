package com.bareminimumstudios.guardian.domain

/**
 * Minecraft-style namespaced identifier without a dependency on Minecraft runtime classes.
 * Keeping domain objects independent prevents live game objects from crossing worker threads.
 */
data class ResourceId(val namespace: String, val path: String) {
    init {
        require(NAMESPACE.matches(namespace)) { "Invalid resource namespace: $namespace" }
        require(PATH.matches(path)) { "Invalid resource path: $path" }
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val PATH = Regex("[a-z0-9/._-]+")

        fun parse(value: String): ResourceId {
            val separator = value.indexOf(':')
            return if (separator < 0) {
                ResourceId("minecraft", value)
            } else {
                ResourceId(value.substring(0, separator), value.substring(separator + 1))
            }
        }
    }
}
