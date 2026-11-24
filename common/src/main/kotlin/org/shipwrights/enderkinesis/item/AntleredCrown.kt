@file:JvmName("AntleredCrown")

package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.petrifyIntoStatue
import org.shipwrights.enderkinesis.duck.AntleredCrownHolder
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.util.damageSource
import java.util.UUID

val PETRIFIED_DAMAGE: ResourceKey<DamageType> =
    ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("petrified"))

val WOOD_HEARTS_PACKET: ResourceLocation = EnderkinesisMod.id("antlered_crown/wood_hearts")

private val WOOD_SLOWNESS_ID: UUID = UUID.fromString("5b0f3c2e-8d1a-4f6b-9c3e-2a7d4e1f0b96")
private const val WOOD_SLOWNESS_NAME = "Antlered Crown wood"

val Player.woodHearts: WoodHearts get() = (this as AntleredCrownHolder).`enderkinesis$woodHearts`()

fun Player.wearsAntleredCrown(): Boolean {
    val crown = EKItems.ANTLERED_CROWN.get()
    return getItemBySlot(EquipmentSlot.HEAD).`is`(crown) || AccessoryGatherer.gather(this).any { it.`is`(crown) }
}

/** The health that `Player.actuallyHurt` sets. Under the crown, the hit turns hearts to wood instead. */
fun healthAfterHit(player: Player, source: DamageSource, newHealth: Float): Float {
    if (player !is ServerPlayer) return newHealth
    val health = player.health
    val damage = health - newHealth
    if (damage <= 0f || source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY) || !player.wearsAntleredCrown()) {
        return newHealth
    }
    val wood = player.woodHearts
    val taken = damage * (1f - woodResistance(woodFraction(wood.halfHearts, player.maxHealth)))
    if (!wood.absorb(taken, health)) return health
    // The death message reads the last combat entry.
    player.combatTracker.recordDamage(player.level().damageSource(PETRIFIED_DAMAGE), taken)
    return 0f
}

/** Called from `ServerPlayer.die` before the inventory drops. */
fun petrifyIfCrowned(player: ServerPlayer) {
    if (!player.isSpectator && player.wearsAntleredCrown()) player.petrifyIntoStatue()
}

fun initAntleredCrown() {
    TickEvent.PLAYER_POST.register { player -> if (player is ServerPlayer) tickWood(player) }
}

private fun tickWood(player: ServerPlayer) {
    val wood = player.woodHearts
    wood.clampTo(player.health)
    wood.regenerate()
    val halfHearts = wood.halfHearts
    val maxHealth = player.maxHealth
    if (halfHearts == wood.sentHalfHearts && maxHealth == wood.sentMaxHealth) return
    wood.sentHalfHearts = halfHearts
    wood.sentMaxHealth = maxHealth
    applySlowness(player, woodSlowness(woodFraction(halfHearts, maxHealth)))
    val buf = FriendlyByteBuf(Unpooled.buffer())
    buf.writeVarInt(halfHearts)
    NetworkManager.sendToPlayer(player, WOOD_HEARTS_PACKET, buf)
}

private fun applySlowness(player: ServerPlayer, slowness: Float) {
    val speed = player.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
    speed.removeModifier(WOOD_SLOWNESS_ID)
    if (slowness <= 0f) return
    speed.addTransientModifier(
        AttributeModifier(
            WOOD_SLOWNESS_ID, WOOD_SLOWNESS_NAME, -slowness.toDouble(), AttributeModifier.Operation.MULTIPLY_TOTAL,
        ),
    )
}
