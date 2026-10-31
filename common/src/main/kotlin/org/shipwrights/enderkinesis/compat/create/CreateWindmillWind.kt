package org.shipwrights.enderkinesis.compat.create

import com.simibubi.create.content.contraptions.ControlledContraptionEntity
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.duck.WindmillWindTarget
import org.shipwrights.enderkinesis.mixin.ControlledContraptionEntityAccessor

/** Incantation of Winds on Create windmills. Call only when Create is loaded. */
object CreateWindmillWind {
    /** Extra sail speed at full wind: 1.0 doubles it. */
    private const val MAX_BOOST = 1.0f

    // Steps, so the falloff does not make the kinetic network update on every tick.
    private const val BOOST_STEP = 0.25f

    /** The boost stays this long after the last gust, so one missed tick does not drop it. */
    private const val HOLD_TICKS = 3L

    /** [wind] is the push falloff at the contact point, 0..1. */
    fun blow(entity: Entity, wind: Float, gameTime: Long) {
        if (entity !is ControlledContraptionEntity) return
        val controller = (entity as ControlledContraptionEntityAccessor).`enderkinesis$getController`()
        val target = controller as? WindmillWindTarget ?: return
        val boost = 1f + Math.round(wind * MAX_BOOST / BOOST_STEP) * BOOST_STEP
        target.`enderkinesis$blow`(boost, gameTime + HOLD_TICKS)
    }
}
