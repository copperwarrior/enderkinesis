package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellMote
import kotlin.random.Random

object SpellTrails {
    fun emit(level: ClientLevel, element: Element, p: Vec3, random: Random, push: Vec3 = Vec3.ZERO) {
        fun jit(s: Double = 1.0) = Vec3((random.nextDouble() - 0.5) * 0.02 * s, (random.nextDouble() - 0.5) * 0.02 * s, (random.nextDouble() - 0.5) * 0.02 * s)
        val (particle: ParticleOptions, v: Vec3) = when (element) {
            Element.PYRE, Element.SUNDERING -> EKParticles.mote(SpellMote.EMBER_FLAME) to jit().add(0.0, 0.012, 0.0)
            Element.WINTER -> EKParticles.mote(SpellMote.FROST) to jit()
            Element.GENESIS -> EKParticles.leafChip() to jit()
            Element.QUINTESSENCE -> EKParticles.mote(SpellMote.PURITY_GLITTER) to jit().add(0.0, 0.006, 0.0)
            Element.GRAVITY -> EKParticles.mote(SpellMote.GRAVITY_DUST) to jit()
            Element.CHAOS -> EKParticles.mote(SpellMote.CHAOS_GLYPH) to jit(2.0)
            Element.BLOOD -> EKParticles.mote(SpellMote.BLOOD_DRIP) to Vec3.ZERO
            Element.SUNFIRE -> EKParticles.mote(SpellMote.SUNFIRE_SPARK) to jit().add(0.0, 0.01, 0.0)
            Element.CORRUPTION -> EKParticles.mote(SpellMote.POISON_SWIRL) to jit().add(0.0, 0.008, 0.0)
            Element.VOID -> if (random.nextFloat() < 0.45f) EKParticles.mote(SpellMote.YGANN_EYE) to jit(0.5)
                else EKParticles.mote(SpellMote.VOID_MOTE) to jit().add(0.0, 0.006, 0.0)
        }
        val m = v.add(push)
        level.addParticle(particle, p.x, p.y, p.z, m.x, m.y, m.z)
    }
}
