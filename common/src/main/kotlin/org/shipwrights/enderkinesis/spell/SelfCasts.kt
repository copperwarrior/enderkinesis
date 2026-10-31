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

/**
 * Self-cast: the held scroll's spell turned on its caster, from its own key (the client's
 * `SpellKeys`). Each element does its own thing to the one casting it ([effect]): Heat cloaks you
 * in flame, Cold in frost, Chaos in lightning, Blood in a cloak that drains whatever comes close,
 * Poison in poison and Void in dark swirls that lash out at enemies; Growth regenerates, Purity
 * drains away what harms you, Gravity anchors you, and Sunfire wreathes you in a Halo. A scroll
 * whose elements do nothing this time isn't spent. One use, and the shared cooldown, as an Area
 * cast is.
 */
object SelfCasts {
    /** C2S: the self-cast key was pressed; carries the hand holding the scroll. */
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
            // Strengthened by the caster's surroundings, as every cast is.
            if (effect(ctx, player, element, magnitude + (ctx.environment[element] ?: 0f))) any = true
        }
        if (!any) return
        MysticScrollItem.spend(level, player, stack)
        MysticScrollItem.startCooldown(player)
    }

    /** What [element] does to [caster] at [magnitude]; false where it does nothing. */
    private fun effect(ctx: SpellContext, caster: ServerPlayer, element: Element, magnitude: Float): Boolean = when (element) {
        // The cloaks' amplifier carries the cast's tier, which sets how hard they bite.
        Element.HEAT -> cloak(caster, EKEffects.FLAME_CLOAK.get(), SpellVisuals.FLAME_CLOAK, magnitude)
        Element.COLD -> cloak(caster, EKEffects.FROST_CLOAK.get(), SpellVisuals.FROST_CLOAK, magnitude)
        Element.GROWTH -> give(caster, MobEffects.REGENERATION, SpellCurves.ticks(5f, 2f, magnitude), SpellCurves.tierAmplifier(magnitude))
            .also { seen(ctx, caster, element, SPIRAL_SEEN) }
        Element.PURITY -> selfCleanse(ctx, caster)
        Element.GRAVITY -> give(caster, EKEffects.ANCHORED.get(), SpellCurves.ticks(5f, 2f, magnitude)).also { seen(ctx, caster, element, PRESS_SEEN) }
        Element.CHAOS -> cloak(caster, EKEffects.LIGHTNING_CLOAK.get(), SpellVisuals.LIGHTNING_CLOAK, magnitude)
        Element.BLOOD -> cloak(caster, EKEffects.ABSORPTION_CLOAK.get(), SpellVisuals.ABSORPTION_CLOAK, magnitude)
        // The Halo's amplifier carries its magnitude, which sets its radius.
        Element.SUNFIRE -> drawnAt(caster, EKEffects.HALO.get(), SpellVisuals.HALO, magnitude, (Math.round(magnitude) - 1).coerceIn(0, 9))
        Element.POISON -> cloak(caster, EKEffects.POISON_CLOAK.get(), SpellVisuals.POISON_CLOAK, magnitude)
        Element.VOID -> cloak(caster, EKEffects.VOID_CLOAK.get(), SpellVisuals.VOID_CLOAK, magnitude)
        Element.SUNDERING -> false
    }

    /** Shows [element]'s self-cast on [caster] for [life] ticks (`SelfCastVisuals`). */
    private fun seen(ctx: SpellContext, caster: ServerPlayer, element: Element, life: Int) =
        SpellVfx.selfCast(ctx.level, caster, element, life)

    /** One of the cloaks, as [visual] (its bit in [SpellVisuals]). */
    private fun cloak(caster: ServerPlayer, effect: MobEffect, visual: Int, magnitude: Float): Boolean =
        drawnAt(caster, effect, visual, magnitude, SpellCurves.tierAmplifier(magnitude))

    /**
     * Gives [caster] [effect] for 8 + 4·[magnitude] seconds at [amplifier]: a cloak or the Halo,
     * drawn as [visual] as dense as it's cast. The magnitude is noted only if the effect takes
     * (a recast weaker and shorter than what's running doesn't), so it's drawn as the effect
     * running is; and sent at once, as the effect's own update went with the one before.
     */
    private fun drawnAt(caster: ServerPlayer, effect: MobEffect, visual: Int, magnitude: Float, amplifier: Int): Boolean {
        if (caster.addEffect(MobEffectInstance(effect, SpellCurves.ticks(8f, 4f, magnitude), amplifier), caster)) {
            SpellVisuals.castMagnitude(caster, visual, magnitude)
            SpellVisuals.update(caster)
        }
        return true
    }

    private fun give(caster: ServerPlayer, effect: MobEffect, ticks: Int, amplifier: Int = 0): Boolean {
        caster.addEffect(MobEffectInstance(effect, ticks, amplifier), caster)
        return true
    }

    /** Purity: the harmful effects on you drain away; the rest stay. */
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

    /** How long each self-cast is seen for, in ticks. */
    private const val SPIRAL_SEEN = 24
    private const val PRESS_SEEN = 12
}
