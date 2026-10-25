package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * When the Ygann's Abyss sky shows: in the Abyss itself, and for a player who is Absent
 * (Void), who sees only that sky wherever they are: the End sky, the writhing sea and the
 * watching eyes, with the world itself hidden (see `LevelRendererAbsentMixin`).
 */
object YgannSky {

    /** Whether the local player is Absent. */
    @JvmStatic
    fun absentView(): Boolean = Minecraft.getInstance().player?.hasEffect(EKEffects.ABSENT.get()) == true

    @JvmStatic
    fun isShown(level: ClientLevel): Boolean = level.dimension() == YgannAbyss.LEVEL_KEY || absentView()
}
