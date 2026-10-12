package org.shipwrights.enderkinesis.dimension

/**
 * True while a loaded client chunk near the player holds a Wohlon biome cell. The ambient light
 * mixins read it first, so a world with no Wohlon pays one field read for each light query.
 *
 * In this package, with no client type, because the vanilla light mixin also loads on a
 * dedicated server. The client scanner writes it; mesh worker threads read it.
 */
object WohlonBiomePresence {
    @JvmField @Volatile var nearby: Boolean = false
}
