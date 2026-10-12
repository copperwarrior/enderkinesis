package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import java.util.UUID
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.registry.EKEffects

/** Which players have Sselith Madness. The effect list of a remote player is not kept current by vanilla. */
object SselithMadnessAfflicted {

    // Client thread only.
    private val afflicted = HashSet<UUID>()

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithMadness.AFFLICTED) { buf, ctx ->
            val update = SselithMadness.readAfflicted(buf)
            ctx.queue {
                if (update.replaceAll) afflicted.clear()
                if (update.afflicted) afflicted.addAll(update.players) else afflicted.removeAll(update.players.toSet())
            }
        }
    }

    fun onDisconnect() = afflicted.clear()

    @JvmStatic
    fun isAfflicted(player: Player): Boolean =
        if (player is LocalPlayer) player.hasEffect(EKEffects.sselithMadnessEffect()) else player.uuid in afflicted
}
