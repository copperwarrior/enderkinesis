package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.ElementalEnvironment
import java.util.UUID

object MundaneIncubation {
    val S2C_STATE: ResourceLocation = EnderkinesisMod.id("mundane_incubation")
    private const val NAME = "enderkinesis_mundane_incubation"
    const val INCUBATION_TICKS = 20 * 60 * 10
    private const val STEP_TICKS = 20
    private const val SECOND_TICKS = 20
    private const val FUSION_SHARDS = 7
    private const val STIR_MIN_TICKS = 20 * 60
    private const val STIR_MAX_TICKS = 20 * 120
    private const val STIR_VOLUME_MIN = 0.4f
    private const val STIR_VOLUME_MAX = 1f
    private const val UNSCHEDULED = Int.MIN_VALUE

    // stirAt is a remaining-ticks mark. It is not saved: after a load, the next stir is scheduled again.
    private class Incubation(
        var remaining: Int,
        val points: FloatArray = FloatArray(Element.entries.size),
        var stirAt: Int = UNSCHEDULED,
    )

    private class Data : SavedData() {
        val byPlayer = HashMap<UUID, Incubation>()

        override fun save(tag: CompoundTag): CompoundTag {
            val list = ListTag()
            for ((uuid, incubation) in byPlayer) {
                val entry = CompoundTag()
                entry.putUUID("UUID", uuid)
                entry.putInt("Remaining", incubation.remaining)
                val points = CompoundTag()
                for (element in Element.entries) {
                    val value = incubation.points[element.ordinal]
                    if (value > 0f) points.putFloat(element.id, value)
                }
                entry.put("Points", points)
                list.add(entry)
            }
            tag.put("Incubations", list)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): Data {
                val data = Data()
                for (raw in tag.getList("Incubations", Tag.TAG_COMPOUND.toInt())) {
                    val entry = raw as CompoundTag
                    val incubation = Incubation(entry.getInt("Remaining"))
                    val points = entry.getCompound("Points")
                    for (key in points.allKeys) {
                        val element = Element.byId(key) ?: continue
                        incubation.points[element.ordinal] = points.getFloat(key)
                    }
                    data.byPlayer[entry.getUUID("UUID")] = incubation
                }
                return data
            }
        }
    }

    private fun data(server: MinecraftServer): Data =
        server.overworld().dataStorage.computeIfAbsent(Data::load, ::Data, NAME)

    // Client thread only: the last state the server sent for the local player.
    var clientIncubating = false

    /** Both sides: the server reads its saved data, the client the synced state. */
    fun incubating(player: Player): Boolean =
        if (player is ServerPlayer) data(player.server).byPlayer.containsKey(player.uuid) else clientIncubating

    /** False when a stone already incubates in [player]. */
    fun start(player: ServerPlayer): Boolean {
        val data = data(player.server)
        if (data.byPlayer.containsKey(player.uuid)) return false
        val incubation = Incubation(INCUBATION_TICKS)
        data.byPlayer[player.uuid] = incubation
        data.setDirty()
        mark(player, incubation)
        return true
    }

    fun init() {
        TickEvent.SERVER_POST.register(TickEvent.Server { server ->
            if (server.tickCount % STEP_TICKS != 0) return@Server
            val data = data(server)
            if (data.byPlayer.isEmpty()) return@Server
            for (player in server.playerList.players) {
                if (!player.isAlive || dreaming(player)) continue
                val incubation = data.byPlayer[player.uuid] ?: continue
                advance(data, player, incubation, 1, playStir = true)
            }
        })
        PlayerEvent.PLAYER_JOIN.register { player ->
            val incubation = data(player.server).byPlayer[player.uuid]
            if (incubation != null) mark(player, incubation) else send(player, null)
        }
    }

    // Each step adds the current place and state once, so a skip counts as that time spent here.
    private fun advance(data: Data, player: ServerPlayer, incubation: Incubation, steps: Int, playStir: Boolean): Int {
        val counted = minOf(steps, (incubation.remaining + STEP_TICKS - 1) / STEP_TICKS)
        for ((element, value) in ElementalEnvironment.of(player.serverLevel(), player)) {
            incubation.points[element.ordinal] += value * counted
        }
        incubation.remaining -= counted * STEP_TICKS
        if (incubation.remaining <= 0) {
            data.byPlayer.remove(player.uuid)
            hatch(player, incubation)
        } else {
            mark(player, incubation)
            stir(player, incubation, playStir)
        }
        data.setDirty()
        return counted
    }

    // A skip only schedules the next stir, so it does not play a burst of them.
    private fun stir(player: ServerPlayer, incubation: Incubation, play: Boolean) {
        if (incubation.stirAt != UNSCHEDULED && incubation.remaining > incubation.stirAt) return
        if (play && incubation.stirAt != UNSCHEDULED) {
            val progress = 1f - incubation.remaining.toFloat() / INCUBATION_TICKS
            val volume = STIR_VOLUME_MIN + (STIR_VOLUME_MAX - STIR_VOLUME_MIN) * progress
            player.playNotifySound(EKSounds.MUNDANE_STONE_STIR.get(), SoundSource.PLAYERS, volume, 1f)
        }
        incubation.stirAt = incubation.remaining - (STIR_MIN_TICKS + player.random.nextInt(STIR_MAX_TICKS - STIR_MIN_TICKS + 1))
    }

    /** `/enderkinesis incubation skip`: the seconds counted, or 0 when no stone incubates in a living, awake [player]. */
    fun skip(player: ServerPlayer, seconds: Int): Int {
        require(seconds > 0) { "seconds must be positive: $seconds" }
        if (!player.isAlive || dreaming(player)) return 0
        val data = data(player.server)
        val incubation = data.byPlayer[player.uuid] ?: return 0
        val counted = advance(data, player, incubation, seconds * SECOND_TICKS / STEP_TICKS, playStir = false)
        return counted * STEP_TICKS / SECOND_TICKS
    }

    // Before the inventory drops, so the stone drops with it, or stays with keepInventory.
    @JvmStatic
    fun onDeath(player: ServerPlayer) {
        if (dreaming(player)) return
        val data = data(player.server)
        data.byPlayer.remove(player.uuid) ?: return
        data.setDirty()
        val stone = ItemStack(EKItems.MUNDANE_STONE.get())
        if (!player.inventory.add(stone)) player.drop(stone, true, false)
        send(player, null)
    }

    // The incubation pauses in the dream. A death there is a wake, and the dream starts with no effects.
    private fun dreaming(player: ServerPlayer): Boolean = player.level().dimension() == Sureibjin.LEVEL_KEY

    private fun mark(player: ServerPlayer, incubation: Incubation) {
        if (!dreaming(player) && !player.hasEffect(EKEffects.INCUBATION.get())) {
            player.addEffect(MobEffectInstance(EKEffects.INCUBATION.get(), incubation.remaining, 0, false, false, true))
        }
        send(player, incubation)
    }

    private fun send(player: ServerPlayer, incubation: Incubation?) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeFloat(incubation?.let { 1f - it.remaining.toFloat() / INCUBATION_TICKS } ?: -1f)
        buf.writeInt(incubation?.let { mixColour(it.points) } ?: 0)
        NetworkManager.sendToPlayer(player, S2C_STATE, buf)
    }

    private fun mixColour(points: FloatArray): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        var total = 0f
        for (element in Element.entries) {
            val weight = points[element.ordinal]
            if (weight <= 0f) continue
            r += weight * (element.color shr 16 and 0xFF)
            g += weight * (element.color shr 8 and 0xFF)
            b += weight * (element.color and 0xFF)
            total += weight
        }
        if (total <= 0f) return Element.QUINTESSENCE.color
        return ((r / total).toInt() shl 16) or ((g / total).toInt() shl 8) or (b / total).toInt()
    }

    // With no points at all, nothing happened, which is Quintessence.
    private fun hatchElement(points: FloatArray): Element =
        Element.entries.filter { it != Element.SUNDERING && points[it.ordinal] > 0f }
            .maxByOrNull { points[it.ordinal] } ?: Element.QUINTESSENCE

    private fun hatchItems(player: ServerPlayer, element: Element): List<ItemStack> {
        if (!element.isFusion) return listOf(ItemStack(checkNotNull(EKItems.gemOf(element))))
        val random = player.random
        return List(FUSION_SHARDS) {
            val component = element.components[random.nextInt(element.components.size)]
            ItemStack(checkNotNull(EKItems.shardOf(component)))
        }
    }

    private fun hatch(player: ServerPlayer, incubation: Incubation) {
        val level = player.serverLevel()
        val x = player.x
        val y = player.y + player.bbHeight * 0.55
        val z = player.z
        level.sendParticles(ItemParticleOption(ParticleTypes.ITEM, ItemStack(EKItems.MUNDANE_STONE.get())), x, y, z, 24, 0.2, 0.25, 0.2, 0.12)
        level.sendParticles(DustParticleOptions(BLOOD, 1.4f), x, y, z, 40, 0.3, 0.35, 0.3, 0.0)
        // The hurt shake and red flash, with no damage and no hurt sound.
        level.chunkSource.broadcastAndSend(player, ClientboundHurtAnimationPacket(player))
        level.playSound(null, x, y, z, EKSounds.MUNDANE_STONE_HATCH.get(), SoundSource.PLAYERS, 1f, 1f)
        player.removeEffect(EKEffects.INCUBATION.get())
        send(player, null)
        for (stack in hatchItems(player, hatchElement(incubation.points))) {
            if (!player.inventory.add(stack)) player.drop(stack, false)
        }
    }

    private val BLOOD = Vector3f(0.55f, 0.03f, 0.05f)
}
