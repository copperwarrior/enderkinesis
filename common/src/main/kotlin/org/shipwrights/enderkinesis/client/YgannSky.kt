package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.registry.EKEffects

/** An Absent player sees this sky in all dimensions. `LevelRendererAbsentMixin` hides the world. */
object YgannSky {

    @JvmStatic
    fun absentView(): Boolean = Minecraft.getInstance().player?.hasEffect(EKEffects.ABSENT.get()) == true

    @JvmStatic
    fun isShown(level: ClientLevel): Boolean = level.dimension() == YgannAbyss.LEVEL_KEY || absentView()
}
