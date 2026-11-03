package org.shipwrights.enderkinesis.spell

import dev.architectury.networking.NetworkManager
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.registry.EKEffects

/** A scroll whose elements do nothing this time is not spent. */
object SelfCasts {
    val SELF_CAST: ResourceLocation = EnderkinesisMod.id("spell/self_cast")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SELF_CAST) { buf, ctx ->
            val hand = buf.readEnum(InteractionHand::class.java)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { start(player, hand) }
        }
    }

    private fun start(player: ServerPlayer, hand: InteractionHand) {
        UlderScrollItem.spellOf(player.getItemInHand(hand)) ?: return
        if (player.isUsingItem || UlderScrollItem.coolingDown(player)) return
        UlderScrollItem.startCharge(player.level(), player, hand, UlderScrollItem.Companion.ChargeMode.SELF)
    }

    /** Returns false where no element did anything. [scale] is the charge power. */
    fun cast(player: ServerPlayer, spell: Spell, scale: Float, charge: Float): Boolean {
        val ctx = SpellContext(player.serverLevel(), player, player.position())
        ctx.elements = spell.magnitudes.keys
        var any = false
        for ((element, magnitude) in spell.magnitudes) {
            val m = magnitude * scale + (ctx.environment[element] ?: 0f)
            if (!effect(ctx, player, element, m)) continue
            any = true
            SpellSounds.of(element)?.let {
                SpellSounds.play(ctx.level, player.position(), it.selfRelease, SpellSounds.chargeVolume(charge) * SpellSounds.magnitudeVolume(m))
            }
        }
        return any
    }

    /** Returns false where [element] does nothing. */
    private fun effect(ctx: SpellContext, caster: ServerPlayer, element: Element, magnitude: Float): Boolean = when (element) {
        // The cloak amplifier holds the cast tier, which sets how hard the cloak bites.
        Element.PYRE -> cloak(caster, EKEffects.MANTLE_OF_FLAME.get(), SpellVisuals.FLAME_CLOAK, magnitude)
        Element.WINTER -> cloak(caster, EKEffects.MANTLE_OF_FROST.get(), SpellVisuals.FROST_CLOAK, magnitude)
        Element.GENESIS -> give(caster, MobEffects.REGENERATION, SpellCurves.ticks(5f, 2f, magnitude), SpellCurves.tierAmplifier(magnitude))
            .also { if (it) seen(ctx, caster, element, SPIRAL_SEEN) }
        Element.QUINTESSENCE -> selfCleanse(ctx, caster)
        Element.GRAVITY -> give(caster, EKEffects.ANCHORED.get(), SpellCurves.ticks(5f, 2f, magnitude)).also { if (it) seen(ctx, caster, element, PRESS_SEEN) }
        Element.CHAOS -> cloak(caster, EKEffects.MANTLE_OF_LIGHTNING.get(), SpellVisuals.LIGHTNING_CLOAK, magnitude)
        Element.BLOOD -> cloak(caster, EKEffects.MANTLE_OF_ABSORPTION.get(), SpellVisuals.ABSORPTION_CLOAK, magnitude)
        // The Halo amplifier holds its magnitude, which sets its radius.
        Element.SUNFIRE -> drawnAt(caster, EKEffects.PALE_CROWN.get(), SpellVisuals.HALO, magnitude, (Math.round(magnitude) - 1).coerceIn(0, 9))
        Element.CORRUPTION -> cloak(caster, EKEffects.MANTLE_OF_POISON.get(), SpellVisuals.POISON_CLOAK, magnitude)
        Element.VOID -> cloak(caster, EKEffects.MANTLE_OF_VOID.get(), SpellVisuals.VOID_CLOAK, magnitude)
        Element.SUNDERING -> false
    }

    private fun seen(ctx: SpellContext, caster: ServerPlayer, element: Element, life: Int) =
        SpellVfx.selfCast(ctx.level, caster, element, life)

    /** [visual] is its bit in [SpellVisuals]. */
    private fun cloak(caster: ServerPlayer, effect: MobEffect, visual: Int, magnitude: Float): Boolean =
        drawnAt(caster, effect, visual, magnitude, SpellCurves.tierAmplifier(magnitude))

    /** Note the magnitude only if the effect takes, so the visual agrees with the running effect.
     *  Send at once, because the effect update went with the previous one. */
    private fun drawnAt(caster: ServerPlayer, effect: MobEffect, visual: Int, magnitude: Float, amplifier: Int): Boolean {
        val took = caster.addEffect(MobEffectInstance(effect, SpellCurves.ticks(8f, 4f, magnitude), amplifier), caster)
        if (took) {
            SpellVisuals.castMagnitude(caster, visual, magnitude)
            SpellVisuals.update(caster)
        }
        return took
    }

    private fun give(caster: ServerPlayer, effect: MobEffect, ticks: Int, amplifier: Int = 0): Boolean =
        caster.addEffect(MobEffectInstance(effect, ticks, amplifier), caster)

    private fun selfCleanse(ctx: SpellContext, caster: ServerPlayer): Boolean {
        val harmful = caster.activeEffects.map { it.effect }.filter {
            it.category == MobEffectCategory.HARMFUL && !BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it).`is`(NamedEffects.CLEANSE_EXCLUSIONS)
        }
        for (effect in harmful) {
            caster.removeEffect(effect)
            SpellVfx.cleanseDrain(ctx.level, caster, effect.color)
        }
        return harmful.isNotEmpty()
    }

    /** Self-cast visual life, in ticks. */
    private const val SPIRAL_SEEN = 24
    private const val PRESS_SEEN = 12
}
