package org.shipwrights.enderkinesis.client

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.Ship

/**
 * Client-side per-column classification of the ship's hull, used by the mesh renderer to cull
 * the virtual sea cleanly through the ship's footprint.
 *
 * Three states per ship-local column:
 *
 *  - **EMPTY** — no non-air block in this column at any Y.
 *  - **BOUNDARY** — has at least one non-air block, but ≥1 of its four cardinal neighbours is
 *    [EMPTY]. These are the hull's edge columns.
 *  - **INTERIOR** — has blocks AND all four cardinal neighbours have blocks. Deep interior.
 *
 * The renderer culls **interior** columns (alpha = 0), keeps **empty** columns (alpha = 1),
 * and renders **boundary** columns at partial alpha so the visual mesh meets the hull's
 * silhouette and fades into it — eliminating the "stops a block short" perception of a pure
 * outward fade *and* the harsh stair-step of a binary cull.
 *
 *  - **`!isAir` per block** — matches the server's `scanShipBlocks` (line 770), so slabs,
 *    stairs, fences, and other partial blocks count as hull. "Inclusive of partial blocks."
 *  - **Lazy rebuild on [TTL_TICKS] cadence** so hull edits propagate. Pass-1 fills hasBlock,
 *    pass-2 classifies into the 3 states via cardinal-neighbour check.
 */
object CrepusculiteLatticeFootprintCache {

    private const val TTL_TICKS: Long = 200L

    /** State byte values. */
    const val EMPTY: Byte = 0
    const val BOUNDARY: Byte = 1
    const val INTERIOR: Byte = 2

    /** Empty column that is **not** reachable from outside the ship by a 2D XZ flood-fill —
     *  i.e. an interior pocket (cabin, cargo hold, sealed chamber). Hard-culled like
     *  [INTERIOR] so the mesh doesn't leak into it during motion. Distinguished from
     *  [INTERIOR] only for diagnostic clarity; both alpha to 0 in the renderer. */
    const val ENCLOSED: Byte = 3

    /** Exterior-empty column with ≥1 cardinal neighbour that's a hull column ([BOUNDARY]
     *  or [INTERIOR]). This is where the **soft-fade** lives — the open-water cells just
     *  outside the ship.
     *
     *  Why outside the hull instead of on it: tiles span multiple columns and the GPU lerps
     *  per-vertex alpha across the quad. If the hull column carries the partial alpha,
     *  interpolation bleeds that partial alpha across the tile into the cells on the other
     *  side of the hull — visible as the mesh "leaking" into enclosed areas from the sides.
     *  Putting the fade in the adjacent *empty* cells keeps the gradient entirely outside the
     *  ship: the hull column is hard-cull (0), so any tile spanning hull → interior has both
     *  corners at 0 and contributes nothing inside. */
    const val EMPTY_NEAR_HULL: Byte = 4

    private class Entry(
        val builtAtGameTime: Long,
        val minLocalX: Int, val minLocalZ: Int,
        val w: Int, val h: Int,
        val state: ByteArray,
    )

    // In singleplayer the server thread (fluid probe) and the render thread use this object
    // at the same time, and a ship id is the same on the two sides.
    private class Side {
        val cache = HashMap<Long, Entry>()
        val tmpVec = Vector3d()
        val tmpPos = BlockPos.MutableBlockPos()
    }

    private val clientSide = Side()
    private val serverSide = Side()

    private fun sideOf(level: Level): Side = if (level.isClientSide) clientSide else serverSide

    private const val MAX_ENTRIES = 256

    /** Look up the 5-state classification at world `(wx, wy, wz)` for the given ship. Returns
     *  [EMPTY] for points outside the ship's local AABB. Discrete — see [alphaAt] for the
     *  smoothed sampler the renderer uses. Caller supplies `worldToShip` so the same cache
     *  serves visual code (`ClientShip.renderTransform.worldToShip` — frame-coherent) and
     *  physics code (`Ship.transform.worldToShip` — server-physics-tick coherent). */
    fun stateAt(level: Level, ship: Ship, worldToShip: Matrix4dc, wx: Double, wy: Double, wz: Double): Byte {
        val entry = entryFor(level, ship) ?: return EMPTY

        val tmpVec = sideOf(level).tmpVec
        tmpVec.set(wx, wy, wz)
        worldToShip.transformPosition(tmpVec)
        val lx = Math.floor(tmpVec.x).toInt()
        val lz = Math.floor(tmpVec.z).toInt()
        val cx = lx - entry.minLocalX
        val cz = lz - entry.minLocalZ
        if (cx < 0 || cz < 0 || cx >= entry.w || cz >= entry.h) return EMPTY
        return entry.state[cz * entry.w + cx]
    }

    /** Convenience overload for the visual code path — picks the [ClientShip]'s frame-coherent
     *  `renderTransform.worldToShip` automatically. */
    fun stateAt(level: Level, ship: ClientShip, wx: Double, wy: Double, wz: Double): Byte =
        stateAt(level, ship, ship.renderTransform.worldToShip, wx, wy, wz)

    /** Smoothed alpha factor at world `(wx, wy, wz)` for the given ship, in [0, 1].
     *
     *  Bilinearly interpolates the 4 cache cells around the query's ship-local *sub-cell*
     *  position. Discrete state-bin lookup ([stateAt]) snaps alpha values from one state to
     *  another whenever a vertex crosses a cell boundary — and because mesh vertices cross
     *  cell boundaries every frame as the ship moves, that produces visible flicker (the
     *  user-reported "quads immediately become cutout, and vice versa, permutated"). Bilinear
     *  sampling at the continuous sub-cell offset makes the alpha continuous across motion:
     *  the four cell weights vary smoothly with the ship-local position, so the per-vertex
     *  alpha varies smoothly too — no per-frame snap.
     *
     *  Hull / interior / enclosed states are weighted at 0 (cull), exterior-near-hull at
     *  [ALPHA_NEAR_HULL], and far-exterior at 1. Tile straddling the hull → 0 alpha through
     *  the entire interior portion (since both BOUNDARY and INTERIOR/ENCLOSED are 0), so no
     *  alpha bleeds across the hull into enclosed cells. */
    fun alphaAt(level: Level, ship: ClientShip, wx: Double, wy: Double, wz: Double): Float {
        val entry = entryFor(level, ship) ?: return 1f
        if (entry.w <= 0 || entry.h <= 0) return 1f

        val tmpVec = sideOf(level).tmpVec
        tmpVec.set(wx, wy, wz)
        // alphaAt is visual-code-only; ClientShip → renderTransform stays.
        ship.renderTransform.worldToShip.transformPosition(tmpVec)
        // Shift by -0.5 so a query at the cell *centre* samples just that cell (weights
        // collapse to 1/0/0/0). Without the shift, a query at the integer corner samples 4
        // cells equally — fine for transitions, but it places "no blending" at the corner
        // instead of the centre, which is the more natural sampling phase.
        val sx = tmpVec.x - 0.5
        val sz = tmpVec.z - 0.5
        val baseLx = Math.floor(sx).toInt()
        val baseLz = Math.floor(sz).toInt()
        val fx = (sx - baseLx).toFloat()
        val fz = (sz - baseLz).toFloat()
        val cx0 = baseLx - entry.minLocalX
        val cz0 = baseLz - entry.minLocalZ

        val a00 = sampleAlpha(entry, cx0,     cz0    )
        val a10 = sampleAlpha(entry, cx0 + 1, cz0    )
        val a01 = sampleAlpha(entry, cx0,     cz0 + 1)
        val a11 = sampleAlpha(entry, cx0 + 1, cz0 + 1)

        val top = a00 + (a10 - a00) * fx
        val bot = a01 + (a11 - a01) * fx
        return top + (bot - top) * fz
    }

    /** Cells outside the cached AABB are treated as far-exterior (alpha 1) — same as the
     *  discrete sampler's default. */
    /** Immutable, thread-safe capture of one ship's footprint for off-thread mesh
     *  builds — own transform copy and scratch vector, backed by the entry's
     *  immutable state array. */
    class Snapshot internal constructor(
        private val minLocalX: Int, private val minLocalZ: Int,
        private val w: Int, private val h: Int,
        private val state: ByteArray,
        private val worldToShip: org.joml.Matrix4d,
    ) {
        private val tmp = Vector3d()

        fun alphaAt(wx: Double, wy: Double, wz: Double): Float {
            tmp.set(wx, wy, wz)
            worldToShip.transformPosition(tmp)
            val sx = tmp.x - 0.5
            val sz = tmp.z - 0.5
            val baseLx = Math.floor(sx).toInt()
            val baseLz = Math.floor(sz).toInt()
            val fx = (sx - baseLx).toFloat()
            val fz = (sz - baseLz).toFloat()
            val cx0 = baseLx - minLocalX
            val cz0 = baseLz - minLocalZ
            val a00 = sample(cx0, cz0)
            val a10 = sample(cx0 + 1, cz0)
            val a01 = sample(cx0, cz0 + 1)
            val a11 = sample(cx0 + 1, cz0 + 1)
            val top = a00 + (a10 - a00) * fx
            val bot = a01 + (a11 - a01) * fx
            return top + (bot - top) * fz
        }

        private fun sample(cx: Int, cz: Int): Float {
            if (cx < 0 || cz < 0 || cx >= w || cz >= h) return ALPHA_EMPTY
            return when (state[cz * w + cx]) {
                EMPTY -> ALPHA_EMPTY
                EMPTY_NEAR_HULL -> ALPHA_NEAR_HULL
                else -> ALPHA_CULL
            }
        }
    }

    /** Build (render thread — may touch level state) a [Snapshot] for worker use. */
    fun snapshot(level: Level, ship: ClientShip): Snapshot? {
        val entry = entryFor(level, ship) ?: return null
        if (entry.w <= 0 || entry.h <= 0) return null
        return Snapshot(
            entry.minLocalX, entry.minLocalZ, entry.w, entry.h, entry.state,
            org.joml.Matrix4d(ship.renderTransform.worldToShip),
        )
    }

    private fun sampleAlpha(entry: Entry, cx: Int, cz: Int): Float {
        if (cx < 0 || cz < 0 || cx >= entry.w || cz >= entry.h) return ALPHA_EMPTY
        return when (entry.state[cz * entry.w + cx]) {
            EMPTY -> ALPHA_EMPTY
            EMPTY_NEAR_HULL -> ALPHA_NEAR_HULL
            else -> ALPHA_CULL   // BOUNDARY / INTERIOR / ENCLOSED — all hard cull
        }
    }

    private fun entryFor(level: Level, ship: Ship): Entry? {
        val now = level.gameTime
        val cache = sideOf(level).cache
        cache[ship.id]?.takeIf { now - it.builtAtGameTime < TTL_TICKS }?.let { return it }
        if (cache.size >= MAX_ENTRIES) cache.clear()
        return build(level, ship, now).also { cache[ship.id] = it }
    }

    /** Per-state alpha values for the bilinear sampler. Hull-side cells must stay at 0 so
     *  tiles straddling the hull → interior boundary can't bleed alpha across (BOUNDARY +
     *  INTERIOR/ENCLOSED both being 0 means any 2x2 sample fully inside the hull yields 0). */
    private const val ALPHA_EMPTY: Float = 1.0f
    private const val ALPHA_NEAR_HULL: Float = 0.55f
    private const val ALPHA_CULL: Float = 0.0f

    private fun build(level: Level, ship: Ship, gameTime: Long): Entry {
        val aabb = ship.shipAABB ?: return Entry(gameTime, 0, 0, 0, 0, ByteArray(0))
        // Pad the local AABB by 1 column on each XZ side so the flood-fill has a guaranteed
        // exterior ring to start from. Block-scan stays within the original AABB; the padding
        // ring is always `has = false`.
        val pad = 1
        val minX = aabb.minX() - pad; val maxX = aabb.maxX() + pad
        val minZ = aabb.minZ() - pad; val maxZ = aabb.maxZ() + pad
        val minY = aabb.minY(); val maxY = aabb.maxY()
        val w = maxX - minX + 1
        val h = maxZ - minZ + 1
        val has = BooleanArray(w * h)
        val tmpPos = sideOf(level).tmpPos

        // Pass 1: hasBlock per column inside the ship's actual AABB. Padding cells stay false.
        for (lz in aabb.minZ()..aabb.maxZ()) {
            for (lx in aabb.minX()..aabb.maxX()) {
                var found = false
                var ly = minY
                while (ly <= maxY) {
                    tmpPos.set(lx, ly, lz)
                    if (!level.getBlockState(tmpPos).isAir) { found = true; break }
                    ly++
                }
                if (found) has[(lz - minZ) * w + (lx - minX)] = true
            }
        }

        // Pass 2: 2D XZ flood-fill from the padding ring through `!has` cells. Result:
        // `exterior[idx] == true` iff this column is reachable from outside the ship by a
        // path through empty columns. Enclosed pockets (cabin, cargo hold) stay false.
        val exterior = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        // Border ring of the padded grid — all guaranteed `!has`, so all valid starts.
        for (cx in 0 until w) {
            queue.add(0 * w + cx)
            if (h > 1) queue.add((h - 1) * w + cx)
        }
        for (cz in 1 until h - 1) {
            queue.add(cz * w + 0)
            if (w > 1) queue.add(cz * w + (w - 1))
        }
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            if (exterior[idx] || has[idx]) continue
            exterior[idx] = true
            val cx = idx % w
            val cz = idx / w
            if (cx > 0) queue.add(idx - 1)
            if (cx < w - 1) queue.add(idx + 1)
            if (cz > 0) queue.add(idx - w)
            if (cz < h - 1) queue.add(idx + w)
        }

        // Pass 3: 5-state classification.
        //   has=true   AND ≥1 EXTERIOR cardinal           → BOUNDARY        (hull face; hard cull,
        //                                                                    fade is *outside* it)
        //   has=true   AND no EXTERIOR cardinal           → INTERIOR        (inside hull, hard cull)
        //   has=false  AND     EXTERIOR AND ≥1 has neighbour → EMPTY_NEAR_HULL (open water adjacent
        //                                                                       to hull; carries fade)
        //   has=false  AND     EXTERIOR AND no has neighbour → EMPTY          (open water far; full alpha)
        //   has=false  AND NOT EXTERIOR                      → ENCLOSED        (sealed pocket; hard cull)
        val state = ByteArray(w * h)
        for (cz in 0 until h) {
            for (cx in 0 until w) {
                val idx = cz * w + cx
                if (has[idx]) {
                    val nL = cx > 0 && exterior[idx - 1]
                    val nR = cx < w - 1 && exterior[idx + 1]
                    val nU = cz > 0 && exterior[idx - w]
                    val nD = cz < h - 1 && exterior[idx + w]
                    state[idx] = if (nL || nR || nU || nD) BOUNDARY else INTERIOR
                } else if (exterior[idx]) {
                    val hL = cx > 0 && has[idx - 1]
                    val hR = cx < w - 1 && has[idx + 1]
                    val hU = cz > 0 && has[idx - w]
                    val hD = cz < h - 1 && has[idx + w]
                    state[idx] = if (hL || hR || hU || hD) EMPTY_NEAR_HULL else EMPTY
                } else {
                    state[idx] = ENCLOSED
                }
            }
        }
        return Entry(gameTime, minX, minZ, w, h, state)
    }
}
