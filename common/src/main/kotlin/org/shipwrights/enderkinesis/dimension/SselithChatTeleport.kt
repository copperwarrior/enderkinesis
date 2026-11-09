package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.ChatEvent
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.sselith.SselithDustBurst
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getEnclosingShip
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.getShipStoodOn
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Chat-phrase teleport in/out of Sselith's Repertory.
 *
 *  - **Outbound** (any dimension except Sselith): chat containing all
 *    five [CANONICAL_WORDS] in order as consecutive whitespace-
 *    separated tokens (anywhere within a longer message), inside a
 *    7×5×7 box (H=3, V=2) around the player containing at least
 *    [SselithBookshelfScan.MIN_BOOKSHELVES] **Sselith bookshelves** ([EKBlocks.SSELITH_BOOKSHELF]
 *    only — vanilla bookshelves do not qualify), saves the player's
 *    current dimension/position/rotation, then teleports them to
 *    the Nexus of Meridians nearest to their X/Z, at [SSELITH_ARRIVAL_Y]. Bookshelf scan covers both world-
 *    frame blocks and blocks on intersecting VS2 ships.
 *  - **Inbound** (in Sselith): same phrase rule teleports the player
 *    back to the last saved location. The save is consumed on use;
 *    if no save exists the phrase is a no-op.
 *
 * The chat message is always allowed through to broadcast — the phrase is
 * spoken out loud whether or not it triggers a teleport.
 */
object SselithChatTeleport {

    /** The five ritual words, in the canonical order — the holy number 34.5 rendered in
     *  Sselith (`vraestmorocht-schest-kelkargh-skarn-moroch`). All five must appear as
     *  consecutive whitespace-separated tokens in the chat message (in this exact order, no
     *  other words between them) for the invocation to fire. The phrase may sit anywhere
     *  inside a longer message; surrounding (but not inter-word) punctuation is tolerated.
     *
     *  This MUST stay equal to `NumeralConverter.decimalToSselith(34.5)` (the canonical holy
     *  number). The algorithm is the source of truth: if it ever changes, update these words
     *  and the lore to match — never reintroduce the deprecated 4-word form
     *  `vraestmorocht-kelkargh-skarn-moroch`. */
    private val CANONICAL_WORDS = listOf(
        "vraestmorocht",
        "schest",
        "kelkargh",
        "skarn",
        "moroch",
    )

    private const val SSELITH_ARRIVAL_Y = 200.0
    /** Arrival yaw 34.5° / pitch −34.5° — the holy number that names the
     *  invocation itself (`vraestmorocht-schest-kelkargh-skarn-moroch` =
     *  34.5 in the Sselith numeral system) carried into both axes. Pitch
     *  is negated so the player arrives looking up at the holy bearing
     *  instead of down at the floor. Anchors the camera at a deliberate
     *  pose on every crossing rather than carrying the player's pre-cross
     *  rotation over. */
    private const val SSELITH_ARRIVAL_YAW = 34.5f
    private const val SSELITH_ARRIVAL_PITCH = -34.5f

    /** [ship] holds the ship the point stood on, so the return follows that ship when it moves. */
    internal data class ReturnPoint(
        val dimension: ResourceKey<Level>,
        val x: Double, val y: Double, val z: Double,
        val yRot: Float, val xRot: Float,
        val ship: ShipSpot? = null,
    ) {
        /** [x], [y], [z] are SHIPYARD coordinates; [yaw] is the ship's MC yaw at the save. */
        internal data class ShipSpot(val id: Long, val x: Double, val y: Double, val z: Double, val yaw: Float)

        /** A world point, and the yaw the ship turned through since the save. */
        internal data class Landing(val x: Double, val y: Double, val z: Double, val yawChange: Float) {
            /** The point at world offset [dx], [dy], [dz] from the save, turned with the ship. */
            fun offset(dx: Double, dy: Double, dz: Double): Vec3 {
                val turn = Math.toRadians(yawChange.toDouble())
                val cos = Math.cos(turn); val sin = Math.sin(turn)
                return Vec3(x + dx * cos - dz * sin, y + dy, z + dx * sin + dz * cos)
            }
        }

        /**
         * Where the point is now in [level]: on its ship when that ship is still there, else the saved world point.
         * Only server-thread teleports call it.
         */
        @OptIn(GameTickOnly::class)
        fun landing(level: ServerLevel): Landing {
            val spot = ship ?: return Landing(x, y, z, 0f)
            val found = level.shipObjectWorld.allShips.getById(spot.id)
                ?.takeIf { it.chunkClaimDimension == level.dimensionId }
                ?: return Landing(x, y, z, 0f)
            val world = found.transform.shipToWorld.transformPosition(Vector3d(spot.x, spot.y, spot.z))
            return Landing(world.x, world.y, world.z, Mth.wrapDegrees(shipYaw(found) - spot.yaw))
        }

        fun save(tag: CompoundTag): CompoundTag {
            tag.putString("Dim", dimension.location().toString())
            tag.putDouble("X", x)
            tag.putDouble("Y", y)
            tag.putDouble("Z", z)
            tag.putFloat("YRot", yRot)
            tag.putFloat("XRot", xRot)
            ship?.let {
                tag.putLong("Ship", it.id)
                tag.putDouble("ShipX", it.x)
                tag.putDouble("ShipY", it.y)
                tag.putDouble("ShipZ", it.z)
                tag.putFloat("ShipYaw", it.yaw)
            }
            return tag
        }

        companion object {
            /** Where [entity] is now, with the ship it rides or stands on. */
            fun of(entity: Entity): ReturnPoint {
                // The 0.2-block foot probe misses a server player mid-jump or a little off the deck; the dragger keeps the ship.
                val found = getShipMountedTo(entity) ?: entity.getEnclosingShip() ?: entity.getShipStoodOn()
                val spot = found?.let {
                    val local = it.transform.worldToShip.transformPosition(Vector3d(entity.x, entity.y, entity.z))
                    ShipSpot(it.id, local.x, local.y, local.z, shipYaw(it))
                }
                return ReturnPoint(entity.level().dimension(), entity.x, entity.y, entity.z, entity.yRot, entity.xRot, spot)
            }

            fun load(tag: CompoundTag): ReturnPoint? {
                val dim = ResourceLocation.tryParse(tag.getString("Dim")) ?: return null
                val spot = if (tag.contains("Ship")) {
                    ShipSpot(
                        tag.getLong("Ship"), tag.getDouble("ShipX"), tag.getDouble("ShipY"), tag.getDouble("ShipZ"),
                        tag.getFloat("ShipYaw"),
                    )
                } else {
                    null
                }
                return ReturnPoint(
                    ResourceKey.create(Registries.DIMENSION, dim),
                    tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"),
                    tag.getFloat("YRot"), tag.getFloat("XRot"),
                    spot,
                )
            }

            private fun shipYaw(ship: Ship): Float {
                val forward = ship.transform.shipToWorldRotation.transform(Vector3d(0.0, 0.0, 1.0))
                return Math.toDegrees(Math.atan2(-forward.x, forward.z)).toFloat()
            }
        }
    }

    fun init() {
        ChatEvent.RECEIVED.register(ChatEvent.Received { player, message ->
            if (player != null) tryInvoke(player, message.string)
            EventResult.pass()
        })
    }

    /** If [text] contains the holy invocation, perform the in/out teleport. Returns
     *  `true` iff the phrase was present (whether or not the teleport conditions —
     *  bookshelf count, saved return point — were met).
     *
     *  Public so [SselithMadnessChat] can feed it the *corrupted* text: under
     *  Sselith Madness a number like `34.5` translates to the holy phrase, and
     *  speaking it (even by accident, in tongues) should still open the crossing. */
    fun tryInvoke(player: ServerPlayer, text: String): Boolean {
        if (!containsInvocation(text)) return false

        val server = player.server
        val currentDim = player.level().dimension()
        if (currentDim == SselithRepertory.LEVEL_KEY) {
            val saved = SselithReturnSavedData.get(server)
            val rp = saved.peek(player.uuid) ?: return true
            val targetLevel = server.getLevel(rp.dimension) ?: return true
            saved.take(player.uuid)
            val returnMono = EKSounds.SSELITH_RETURN_MONO.get()
            SselithDustBurst.at(player.serverLevel(), player)
            playForOthers(player, returnMono)
            val landing = rp.landing(targetLevel)
            player.teleportTo(targetLevel, landing.x, landing.y, landing.z, rp.yRot + landing.yawChange, rp.xRot)
            playForOthers(player, returnMono)
            player.playNotifySound(EKSounds.SSELITH_RETURN.get(), SoundSource.PLAYERS, 1.0f, 1.0f)
            sendGlyphSeed(player, EKParticles.sselithDepartureAura())
            return true
        }

        val nearShelves = SselithBookshelfScan.collect(
            player.level(), player.x, player.y, player.z, SselithBookshelfScan.MIN_BOOKSHELVES, null,
        )
        if (nearShelves < SselithBookshelfScan.MIN_BOOKSHELVES) return true

        val sselithLevel = server.getLevel(SselithRepertory.LEVEL_KEY) ?: return true
        saveReturnPoint(player)
        val hissMono = EKSounds.SSELITH_HISS_MONO.get()
        SselithDustBurst.at(player.serverLevel(), player)
        playForOthers(player, hissMono)
        player.teleportTo(
            sselithLevel,
            SselithRepertory.nearestMeridian(player.blockX).toDouble(),
            SSELITH_ARRIVAL_Y,
            SselithRepertory.nearestMeridian(player.blockZ).toDouble(),
            SSELITH_ARRIVAL_YAW, SSELITH_ARRIVAL_PITCH,
        )
        playForOthers(player, hissMono)
        player.playNotifySound(EKSounds.SSELITH_HISS.get(), SoundSource.PLAYERS, 1.0f, 1.0f)
        sendGlyphSeed(player, EKParticles.sselithArrivalStorm())
        return true
    }

    /** Save where [player] stands now, so the invocation in Sselith can bring them back. */
    fun saveReturnPoint(player: ServerPlayer) {
        SselithReturnSavedData.get(player.server).put(player.uuid, ReturnPoint.of(player))
    }

    private fun sendGlyphSeed(player: ServerPlayer, seed: SimpleParticleType) {
        val level = player.serverLevel()
        // A count of 0 makes the packet carry (player.id, 0, 0) as the velocity. The force flag
        // stops the client particle limit from dropping the one seed.
        for (viewer in level.players()) {
            level.sendParticles(
                viewer, seed, true, player.x, player.y, player.z, 0, player.id.toDouble(), 0.0, 0.0, 1.0,
            )
        }
    }

    private fun playForOthers(player: ServerPlayer, sound: SoundEvent) {
        player.level().playSound(player, player.x, player.y, player.z, sound, SoundSource.PLAYERS, 1.0f, 1.0f)
    }

    /** True when [message] contains all five [CANONICAL_WORDS] in
     *  canonical order as consecutive tokens. Case-insensitive; the
     *  phrase may appear anywhere inside a longer message.
     *
     *  Tokenisation: split on any run of non-word characters (Java
     *  `\W+` — whitespace, punctuation, hyphens, dashes, commas,
     *  periods…). This covers every form the lore book uses and every
     *  natural typing variant:
     *
     *   - `vraestmorocht schest kelkargh skarn moroch` (plain spaces)
     *   - `vraestmorocht-schest-kelkargh-skarn-moroch` (book form, hyphens
     *     with no spaces — what most readers actually type)
     *   - `vraestmorocht - schest - kelkargh - skarn - moroch` (spaced hyphens)
     *   - `hello, vraestmorocht-schest-kelkargh-skarn-moroch!` (embedded)
     *
     *  All of these tokenise to the same 5-word sequence and match. */
    fun containsInvocation(message: String): Boolean {
        val tokens = message.lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotEmpty() }
        if (tokens.size < CANONICAL_WORDS.size) return false
        val last = tokens.size - CANONICAL_WORDS.size
        for (i in 0..last) {
            var matched = true
            for (k in CANONICAL_WORDS.indices) {
                if (tokens[i + k] != CANONICAL_WORDS[k]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}
