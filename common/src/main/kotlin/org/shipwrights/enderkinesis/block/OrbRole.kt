package org.shipwrights.enderkinesis.block

import net.minecraft.util.StringRepresentable

/**
 * High-level role the Orb of Linking currently plays in the world. Drives
 * the block's static model (one texture per role) and gates the
 * [org.shipwrights.enderkinesis.client.OrbOfLinkingHazeRenderer] pulsing
 * "haze" overlay — invisible for [UNBOUND], visible and pulsing for the
 * other two.
 *
 *  - [UNBOUND] — no links of any kind. The orb has been placed but
 *    nothing's been wired through it yet. Texture: `link_orb`.
 *  - [SEND] — has at least one outgoing link (regardless of whether it
 *    also has incoming). Texture: `orb_transmitter`. SEND wins over
 *    RECEIVE when an orb plays both roles in different tomes; the orb
 *    is doing the driving so its appearance follows the active direction.
 *  - [RECEIVE] — has incoming links only. Texture: `orb_reciever`
 *    (artist spelling; preserved verbatim).
 */
enum class OrbRole(private val key: String) : StringRepresentable {
    UNBOUND("unbound"),
    SEND("send"),
    RECEIVE("receive");

    override fun getSerializedName(): String = key
}
