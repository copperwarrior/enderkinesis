package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.ChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Chat-phrase teleport in/out of Sselith's Repertory.
 *
 *  - **Outbound** (any dimension except Sselith): chat containing all
 *    five [CANONICAL_WORDS] in order as consecutive whitespace-
 *    separated tokens (anywhere within a longer message), inside a
 *    7×5×7 box (H=3, V=2) around the player containing at least
 *    [MIN_BOOKSHELVES] **Sselith bookshelves** ([EKBlocks.SSELITH_BOOKSHELF]
 *    only — vanilla bookshelves do not qualify), saves the player's
 *    current dimension/position/rotation, then teleports them to
 *    [SSELITH_ARRIVAL] in Sselith. Bookshelf scan covers both world-
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

    /** Horizontal Chebyshev radius (blocks) of the bookshelf scan — the
     *  X/Z half-width around the player's block position. The scan box
     *  is (2·H + 1) on X and Z, (2·V + 1) on Y → 7×5×7 at H=3, V=2. */
    private const val BOOKSHELF_RADIUS_H = 3

    /** Vertical Chebyshev radius (blocks) of the bookshelf scan. */
    private const val BOOKSHELF_RADIUS_V = 2

    /** Minimum bookshelves the scan must find to enable an outbound teleport. */
    private const val MIN_BOOKSHELVES = 4

    /** Player arrival point in Sselith. */
    private const val SSELITH_ARRIVAL_X = 0.5
    private const val SSELITH_ARRIVAL_Y = 2.0
    private const val SSELITH_ARRIVAL_Z = 0.5

    private data class ReturnPoint(
        val dimension: ResourceKey<Level>,
        val x: Double, val y: Double, val z: Double,
        val yRot: Float, val xRot: Float,
    )

    /** Per-player saved location, written on outbound teleport, consumed on
     *  inbound teleport. In-memory only; not persisted across restart. */
    private val returnPoints = ConcurrentHashMap<UUID, ReturnPoint>()

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
            val rp = returnPoints.remove(player.uuid) ?: return true
            val targetLevel = server.getLevel(rp.dimension) ?: return true
            player.teleportTo(targetLevel, rp.x, rp.y, rp.z, rp.yRot, rp.xRot)
            return true
        }

        if (!hasEnoughBookshelves(player)) return true

        val sselithLevel = server.getLevel(SselithRepertory.LEVEL_KEY) ?: return true
        returnPoints[player.uuid] = ReturnPoint(
            dimension = currentDim,
            x = player.x, y = player.y, z = player.z,
            yRot = player.yRot, xRot = player.xRot,
        )
        player.teleportTo(
            sselithLevel,
            SSELITH_ARRIVAL_X, SSELITH_ARRIVAL_Y, SSELITH_ARRIVAL_Z,
            player.yRot, player.xRot,
        )
        return true
    }

    /** Counts bookshelves in a (2·H+1)×(2·V+1)×(2·H+1) box around the
     *  player's block position — currently 7×5×7. Counts world-frame
     *  blocks AND blocks on any VS2 ship whose world AABB intersects the
     *  box. Short-circuits as soon as the count reaches [MIN_BOOKSHELVES]. */
    private fun hasEnoughBookshelves(player: ServerPlayer): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val px = Math.floor(player.x).toInt()
        val py = Math.floor(player.y).toInt()
        val pz = Math.floor(player.z).toInt()

        var count = 0

        // World-frame pass — skips positions that belong to a ship (those are
        // counted in the ship pass via ship-frame coords).
        for (x in (px - BOOKSHELF_RADIUS_H)..(px + BOOKSHELF_RADIUS_H)) {
            for (y in (py - BOOKSHELF_RADIUS_V)..(py + BOOKSHELF_RADIUS_V)) {
                for (z in (pz - BOOKSHELF_RADIUS_H)..(pz + BOOKSHELF_RADIUS_H)) {
                    val pos = BlockPos(x, y, z)
                    if (level.getLoadedShipManagingPos(pos) != null) continue
                    if (isBookshelf(level, pos)) {
                        count++
                        if (count >= MIN_BOOKSHELVES) return true
                    }
                }
            }
        }

        // Ship-frame pass — for each ship whose worldAABB intersects the
        // scan box, transform the box to ship frame, scan, re-verify world
        // coords are inside the original box.
        val scanMinX = (px - BOOKSHELF_RADIUS_H).toDouble()
        val scanMaxX = (px + BOOKSHELF_RADIUS_H + 1).toDouble()
        val scanMinY = (py - BOOKSHELF_RADIUS_V).toDouble()
        val scanMaxY = (py + BOOKSHELF_RADIUS_V + 1).toDouble()
        val scanMinZ = (pz - BOOKSHELF_RADIUS_H).toDouble()
        val scanMaxZ = (pz + BOOKSHELF_RADIUS_H + 1).toDouble()

        for (ship in level.shipObjectWorld.allShips) {
            val sa = ship.worldAABB
            if (sa.maxX() < scanMinX || sa.minX() > scanMaxX) continue
            if (sa.maxY() < scanMinY || sa.minY() > scanMaxY) continue
            if (sa.maxZ() < scanMinZ || sa.minZ() > scanMaxZ) continue

            val w2s = ship.transform.worldToShip
            var bxMin = Double.POSITIVE_INFINITY; var bxMax = Double.NEGATIVE_INFINITY
            var byMin = Double.POSITIVE_INFINITY; var byMax = Double.NEGATIVE_INFINITY
            var bzMin = Double.POSITIVE_INFINITY; var bzMax = Double.NEGATIVE_INFINITY
            val cornersX = doubleArrayOf(scanMinX, scanMaxX)
            val cornersY = doubleArrayOf(scanMinY, scanMaxY)
            val cornersZ = doubleArrayOf(scanMinZ, scanMaxZ)
            for (cx in cornersX) for (cy in cornersY) for (cz in cornersZ) {
                val v = Vector3d(cx, cy, cz)
                w2s.transformPosition(v)
                if (v.x < bxMin) bxMin = v.x; if (v.x > bxMax) bxMax = v.x
                if (v.y < byMin) byMin = v.y; if (v.y > byMax) byMax = v.y
                if (v.z < bzMin) bzMin = v.z; if (v.z > bzMax) bzMax = v.z
            }

            val sx0 = Math.floor(bxMin).toInt(); val sx1 = Math.floor(bxMax).toInt()
            val sy0 = Math.floor(byMin).toInt(); val sy1 = Math.floor(byMax).toInt()
            val sz0 = Math.floor(bzMin).toInt(); val sz1 = Math.floor(bzMax).toInt()
            val s2w = ship.transform.shipToWorld
            for (x in sx0..sx1) {
                for (y in sy0..sy1) {
                    for (z in sz0..sz1) {
                        val pos = BlockPos(x, y, z)
                        if (level.getLoadedShipManagingPos(pos)?.id != ship.id) continue
                        if (!isBookshelf(level, pos)) continue
                        val cw = Vector3d(x + 0.5, y + 0.5, z + 0.5)
                        s2w.transformPosition(cw)
                        if (cw.x < scanMinX || cw.x > scanMaxX) continue
                        if (cw.y < scanMinY || cw.y > scanMaxY) continue
                        if (cw.z < scanMinZ || cw.z > scanMaxZ) continue
                        count++
                        if (count >= MIN_BOOKSHELVES) return true
                    }
                }
            }
        }

        return false
    }

    private fun isBookshelf(level: ServerLevel, pos: BlockPos): Boolean {
        val block = level.getBlockState(pos).block
        return block === EKBlocks.SSELITH_BOOKSHELF.get()
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
