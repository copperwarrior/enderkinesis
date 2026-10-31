package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.item.ScalingRobeArmorItem
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

object RobeMotionSounds {

    private const val RANGE_SQR: Double = 32.0 * 32.0
    private const val LAND_MIN_FALL: Double = 0.5
    /** A remote player's onGround arrives with the packet, before the 3-tick position lerp shows the landing. */
    private const val REMOTE_LAND_DELAY_TICKS: Int = 1
    private const val FLUTTER_EASE: Float = 0.05f
    /** Wind strength where the flutter starts, and where it reaches full volume. */
    private const val FLUTTER_MIN_WIND: Double = 0.5
    private const val FLUTTER_FULL_WIND: Double = 1.5
    private const val FLUTTER_MAX_VOLUME: Float = 0.5f
    private const val PITCH_MIN: Float = 0.95f
    private const val PITCH_SPREAD: Float = 0.1f

    /** Remote players do not run Entity.move, so their fallDistance stays still. */
    private class Wearer(player: Player) {
        var seen: Boolean = true
        var onGround: Boolean = player.onGround()
        var peakY: Double = player.y
        var landPlayed: Boolean = false
        var landDelay: Int = 0
        var flutter: FlutterLoop? = null
    }

    /** Client thread only. Keyed by entity id. */
    private val wearers = HashMap<Int, Wearer>()
    private var lastLevel: ClientLevel? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        clear()
        lastLevel = null
    }

    private fun clear() {
        val sounds = Minecraft.getInstance().soundManager
        for (wearer in wearers.values) wearer.flutter?.let(sounds::stop)
        wearers.clear()
    }

    private fun tick(level: ClientLevel) {
        val listener = Minecraft.getInstance().player ?: return
        if (level !== lastLevel) {
            clear()
            lastLevel = level
        }
        for (wearer in wearers.values) wearer.seen = false
        for (player in level.players()) {
            if (player.isSpectator || player.distanceToSqr(listener) > RANGE_SQR) continue
            val set = robeSet(player) ?: continue
            val wearer = wearers.getOrPut(player.id) { Wearer(player) }
            wearer.seen = true
            tickMotion(level, player, wearer)
            tickFlutter(player, set, wearer)
        }
        val sounds = Minecraft.getInstance().soundManager
        val it = wearers.values.iterator()
        while (it.hasNext()) {
            val wearer = it.next()
            if (wearer.seen) continue
            wearer.flutter?.let(sounds::stop)
            it.remove()
        }
    }

    private fun robeSet(player: Player): EKSounds.RobeSet? {
        val piece = player.getItemBySlot(EquipmentSlot.CHEST).item as? ScalingRobeArmorItem
            ?: player.getItemBySlot(EquipmentSlot.LEGS).item as? ScalingRobeArmorItem
        return piece?.robeMaterial?.sounds
    }

    private fun tickMotion(level: ClientLevel, player: Player, wearer: Wearer) {
        val onGround = player.onGround()
        val local = player === Minecraft.getInstance().player
        if (wearer.onGround && !onGround) {
            wearer.peakY = player.yo
            wearer.landPlayed = false
            wearer.landDelay = 0
        }
        if (wearer.landDelay > 0 && --wearer.landDelay == 0) land(level, player)
        if (!onGround) {
            wearer.peakY = maxOf(wearer.peakY, player.y)
            // The landing sound starts before its peak, so the local player's landing plays one tick early.
            val nextY = player.y + player.deltaMovement.y
            val fellFar = wearer.peakY - nextY >= LAND_MIN_FALL
            if (local && !wearer.landPlayed && fellFar && landsNextTick(level, player)) {
                wearer.landPlayed = true
                land(level, player)
            }
        } else if (!wearer.onGround && !wearer.landPlayed && wearer.peakY - player.y >= LAND_MIN_FALL) {
            wearer.landPlayed = true
            if (local) land(level, player) else wearer.landDelay = REMOTE_LAND_DELAY_TICKS
        }
        wearer.onGround = onGround
    }

    /** The next move uses deltaMovement as it is now. A ship deck can miss this; the landing tick plays it then. */
    private fun landsNextTick(level: ClientLevel, player: Player): Boolean {
        val motion = player.deltaMovement
        if (motion.y >= 0.0) return false
        return !level.noCollision(player, player.boundingBox.move(motion.x, motion.y, motion.z))
    }

    private fun land(level: ClientLevel, player: Player) {
        if (player.getItemBySlot(EquipmentSlot.HEAD).`is`(EKItems.BLUE_WITCH_HAT.get())) {
            play(level, player, EKSounds.ROBES_BLUE_WITCH_HAT_FLOP)
        }
    }

    private fun tickFlutter(player: Player, set: EKSounds.RobeSet, wearer: Wearer) {
        val sounds = Minecraft.getInstance().soundManager
        var loop = wearer.flutter
        val hasChest = player.getItemBySlot(EquipmentSlot.CHEST).item is ScalingRobeArmorItem
        // A level change stops all sounds and does not call stop() on the instance.
        if (loop != null && (loop.isStopped || !sounds.isActive(loop) || !hasChest || loop.set !== set)) {
            sounds.stop(loop)
            loop = null
        }
        wearer.flutter = loop
        if (!hasChest) return
        val target = flutterVolume(TightRobeArmorRenderer.INSTANCE.windStrength(player))
        if (loop == null) {
            if (target <= 0f) return
            loop = FlutterLoop(player, set)
            loop.target = target
            sounds.play(loop)
            wearer.flutter = loop
        }
        loop.target = target
    }

    /** Smoothstep from [FLUTTER_MIN_WIND] to [FLUTTER_FULL_WIND], up to [FLUTTER_MAX_VOLUME]. */
    private fun flutterVolume(wind: Double): Float {
        val t = ((wind - FLUTTER_MIN_WIND) / (FLUTTER_FULL_WIND - FLUTTER_MIN_WIND)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat() * FLUTTER_MAX_VOLUME
    }

    private fun play(level: ClientLevel, player: Player, event: RegistrySupplier<SoundEvent>) {
        val pitch = PITCH_MIN + level.random.nextFloat() * PITCH_SPREAD
        level.playLocalSound(player.x, player.y, player.z, event.get(), SoundSource.PLAYERS, 1f, pitch, false)
    }

    private class FlutterLoop(private val player: Player, val set: EKSounds.RobeSet) :
        AbstractTickableSoundInstance(
            set.flutterLoop.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom(),
        ) {

        var target: Float = 0f

        init {
            looping = true
            delay = 0
            volume = 0f
            follow()
        }

        // The ease starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (player.isRemoved) {
                stop()
                return
            }
            follow()
            volume = if (volume < target) {
                (volume + FLUTTER_EASE).coerceAtMost(target)
            } else {
                (volume - FLUTTER_EASE).coerceAtLeast(target)
            }
            if (volume <= 0f && target <= 0f) stop()
        }

        private fun follow() {
            x = player.x
            y = player.y
            z = player.z
        }
    }
}
