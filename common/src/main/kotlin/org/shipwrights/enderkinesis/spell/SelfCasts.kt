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
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.registry.EKEffects

/** A scroll whose elements do nothing this time is not spent. */
object SelfCasts {
    val SELF_CAST: ResourceLocation = EnderkinesisMod.id("spell/self_cast")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SELF_CAST) { buf, ctx ->
            val hand = buf.readEnum(InteractionHand::class.java)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { cast(player, hand) }
        }
    }

    private fun cast(player: ServerPlayer, hand: InteractionHand) {
        val stack = player.getItemInHand(hand)
        val spell = MysticScrollItem.spellOf(stack) ?: return
        if (player.isUsingItem || MysticScrollItem.coolingDown(player)) return
        val level = player.serverLevel()
        val ctx = SpellContext(level, player, player.position())
        ctx.elements = spell.magnitudes.keys
        var any = false
        for ((element, magnitude) in spell.magnitudes) {
            if (effect(ctx, player, element, magnitude + (ctx.environment[element] ?: 0f))) any = true
        }
        if (!any) return
        MysticScrollItem.spend(level, player, stack)
        MysticScrollItem.startCooldown(player)
    }

    /** Returns false where [element] does nothing. */
    private fun effect(ctx: SpellContext, caster: ServerPlayer, element: Element, magnitude: Float): Boolean = when (element) {
        // The cloak amplifier holds the cast tier, which sets how hard the cloak bites.
        Element.HEAT -> cloak(caster, EKEffects.FLAME_CLOAK.get(), SpellVisuals.FLAME_CLOAK, magnitude)
        Element.COLD -> cloak(caster, EKEffects.FROST_CLOAK.get(), SpellVisuals.FROST_CLOAK, magnitude)
        Element.GROWTH -> give(caster, MobEffects.REGENERATION, SpellCurves.ticks(5f, 2f, magnitude), SpellCurves.tierAmplifier(magnitude))
            .also { if (it) seen(ctx, caster, element, SPIRAL_SEEN) }
        Element.PURITY -> selfCleanse(ctx, caster)
        Element.GRAVITY -> give(caster, EKEffects.ANCHORED.get(), SpellCurves.ticks(5f, 2f, magnitude)).also { if (it) seen(ctx, caster, element, PRESS_SEEN) }
        Element.CHAOS -> cloak(caster, EKEffects.LIGHTNING_CLOAK.get(), SpellVisuals.LIGHTNING_CLOAK, magnitude)
        Element.BLOOD -> cloak(caster, EKEffects.ABSORPTION_CLOAK.get(), SpellVisuals.ABSORPTION_CLOAK, magnitude)
        // The Halo amplifier holds its magnitude, which sets its radius.
        Element.SUNFIRE -> drawnAt(caster, EKEffects.HALO.get(), SpellVisuals.HALO, magnitude, (Math.round(magnitude) - 1).coerceIn(0, 9))
        Element.POISON -> cloak(caster, EKEffects.POISON_CLOAK.get(), SpellVisuals.POISON_CLOAK, magnitude)
        Element.VOID -> cloak(caster, EKEffects.VOID_CLOAK.get(), SpellVisuals.VOID_CLOAK, magnitude)
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
