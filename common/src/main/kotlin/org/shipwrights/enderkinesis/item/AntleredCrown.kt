@file:JvmName("AntleredCrown")

package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.petrifyIntoStatue
import org.shipwrights.enderkinesis.duck.AntleredCrownHolder
import org.shipwrights.enderkinesis.registry.EKItems
import java.util.UUID

val WOOD_HEARTS_PACKET: ResourceLocation = EnderkinesisMod.id("antlered_crown/wood_hearts")

private val WOOD_SLOWNESS_ID: UUID = UUID.fromString("5b0f3c2e-8d1a-4f6b-9c3e-2a7d4e1f0b96")
private const val WOOD_SLOWNESS_NAME = "Antlered Crown wood"

val Player.woodHearts: WoodHearts get() = (this as AntleredCrownHolder).`enderkinesis$woodHearts`()

fun Player.wearsAntleredCrown(): Boolean {
    val crown = EKItems.ANTLERED_CROWN.get()
    return getItemBySlot(EquipmentSlot.HEAD).`is`(crown) || AccessoryGatherer.gather(this).any { it.`is`(crown) }
}

private const val ACCESSORY_CHECK_TICKS = 20

// The helmet check is free each tick; the accessory gatherer allocates a list, so its result is cached.
private fun ServerPlayer.wearsAntleredCrownCached(): Boolean {
    val crown = EKItems.ANTLERED_CROWN.get()
    if (getItemBySlot(EquipmentSlot.HEAD).`is`(crown)) return true
    val hearts = woodHearts
    if (hearts.accessoryCheckDue || tickCount % ACCESSORY_CHECK_TICKS == 0) {
        hearts.accessoryWorn = AccessoryGatherer.gather(this).any { it.`is`(crown) }
        hearts.accessoryCheckDue = false
    }
    return hearts.accessoryWorn
}

/** The health that `Player.actuallyHurt` sets. The loss is split between red and wood hearts. */
fun healthAfterHit(player: Player, source: DamageSource, newHealth: Float): Float {
    if (player !is ServerPlayer) return newHealth
    val health = player.health
    val loss = health - newHealth
    if (loss <= 0f) return newHealth
    val worn = player.wearsAntleredCrownCached()
    val wood = player.woodHearts
    // Heals since the last tick become wood before this hit splits.
    wood.observe(health, worn)
    val bypasses = source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)
    val taken = woodDamageTaken(loss, wood.halfHearts, player.maxHealth, bypasses)
    wood.takeHit(health, taken)
    return health - taken
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
    val worn = player.wearsAntleredCrownCached()
    val regrown = wood.regrow(player.health, player.maxHealth, worn)
    if (regrown > 0f) player.heal(regrown)
    wood.observe(player.health, worn)
    val halfHearts = wood.halfHearts
    val maxHealth = player.maxHealth
    if (halfHearts == wood.sentHalfHearts && maxHealth == wood.sentMaxHealth && worn == wood.sentWorn) return
    wood.sentHalfHearts = halfHearts
    wood.sentMaxHealth = maxHealth
    wood.sentWorn = worn
    val fraction = woodFraction(halfHearts, maxHealth)
    applySlowness(player, woodSlowness(fraction))
    (player as AntleredCrownHolder).`enderkinesis$setVeinGrowth`(fraction)
    val buf = FriendlyByteBuf(Unpooled.buffer())
    buf.writeVarInt(halfHearts)
    buf.writeBoolean(worn)
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
