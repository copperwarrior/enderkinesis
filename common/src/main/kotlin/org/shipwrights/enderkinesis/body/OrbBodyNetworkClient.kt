package org.shipwrights.enderkinesis.body

import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.FractalProjectorBlock

/**
 * Client-only S2C receivers for the Orb of Potential body sync.
 *
 *  Fractal pattern type is computed from the anchor as packets arrive
 *  rather than read from the wire — both sides agree on the SplitMix64
 *  mixer in [FractalProjectorBlock.computeFractalType].
 *
 *  Split out of [OrbBodyNetwork] so the dedicated server doesn't drag
 *  the client `Minecraft` class onto its classloader.
 */
object OrbBodyNetworkClient {

    fun initClient() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, OrbBodyNetwork.ORB_ADDED) { buf, _ ->
            val bodyId = buf.readLong()
            val anchor = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val ft = FractalProjectorBlock.computeFractalType(anchor)
            Minecraft.getInstance().execute { ClientOrbRegistry.add(bodyId, anchor, ft) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, OrbBodyNetwork.ORB_REMOVED) { buf, _ ->
            val bodyId = buf.readLong()
            Minecraft.getInstance().execute { ClientOrbRegistry.remove(bodyId) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, OrbBodyNetwork.ORB_FULL_LIST) { buf, _ ->
            val count = buf.readVarInt()
            val list = ArrayList<Pair<Long, ClientOrbRegistry.Entry>>(count)
            for (i in 0 until count) {
                val id = buf.readLong()
                val anchor = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
                val ft = FractalProjectorBlock.computeFractalType(anchor)
                list += id to ClientOrbRegistry.Entry(anchor, ft)
            }
            Minecraft.getInstance().execute { ClientOrbRegistry.replaceForDim(list) }
        }
    }
}
