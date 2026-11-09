// Jet binning runs only on the render thread.
package org.shipwrights.enderkinesis.client

import com.mojang.logging.LogUtils
import org.lwjgl.BufferUtils
import org.shipwrights.enderkinesis.spell.GasKind
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Bins the jet lines of a frame into 1-block tiles, for the volume shader. Tiles live in 8-block pages; a page table
 * (one cell for each 8 blocks of a volume box) points to pages in one shared atlas. A tile entry gives the runs of
 * samples (one run for each jet that passes the tile, at most 8) in a shared run list. Nothing allocates in a frame.
 */
object GasJetTiles {
    /** One volume that holds jets: its page table, in the box-relative blocks of that volume. */
    class JetVolume {
        var kind = GasKind.MIASMA
        var frame = 0L
        var minX = 0; var minY = 0; var minZ = 0
        var w = 0; var h = 0; var d = 0
        val table = BufferUtils.createShortBuffer(GasJetLines.TABLE_X * GasJetLines.TABLE_Y * GasJetLines.TABLE_Z)
    }

    private const val MAX_VOLUMES = 32
    private const val RECORD_CAP = 32768
    private const val LINE_AXIS = 32
    private const val LINE_TILES = LINE_AXIS * LINE_AXIS * LINE_AXIS
    private const val KEYS = GasJetLines.ATLAS_PAGES * GasJetLines.PAGE_TILES
    private const val REACH = 0.87
    private const val FIXED_BIAS = 64f
    private const val FIXED_SCALE = 256f

    private val volumes = Array(MAX_VOLUMES) { JetVolume() }
    private var volumeCount = 0

    /** Samples as RGBA32UI texels in fixed point (see [writeSamples]). */
    val sampleTexels = BufferUtils.createIntBuffer(GasJetLines.SAMPLE_BUDGET * 4)
    val runTexels = BufferUtils.createIntBuffer(GasJetLines.RUN_CAP)
    private val pageBuffer = BufferUtils.createIntBuffer(GasJetLines.PAGE_TILES)
    private val pageData = IntArray(GasJetLines.ATLAS_PAGES * GasJetLines.PAGE_TILES)
    var pagesUsed = 0
        private set
    var runCount = 0
        private set

    // Per-line scratch over its tile box.
    private val lineLo = IntArray(LINE_TILES)
    private val lineHi = IntArray(LINE_TILES)
    private val lineC = FloatArray(LINE_TILES)
    private val lineR = FloatArray(LINE_TILES)
    private val lineIdx = IntArray(GasJetLines.MAX_LINES)
    // Records of one volume: a run of one jet in one tile.
    private val recKey = IntArray(RECORD_CAP)
    private val recRun = IntArray(RECORD_CAP)
    private val recScore = FloatArray(RECORD_CAP)
    private val recSplat = FloatArray(RECORD_CAP)
    private val pageCx = IntArray(GasJetLines.ATLAS_PAGES)
    private val pageCy = IntArray(GasJetLines.ATLAS_PAGES)
    private val pageCz = IntArray(GasJetLines.ATLAS_PAGES)
    private var atlasFullLogged = false
    private val log = LogUtils.getLogger()

    // Tiles whose runs did not fit: the volume build splats them into the 1-block field. Read one frame later, by the build.
    private const val DROP_CAP = 4096
    val dropKind = IntArray(DROP_CAP)
    val dropFrame = LongArray(DROP_CAP)
    val dropX = IntArray(DROP_CAP); val dropY = IntArray(DROP_CAP); val dropZ = IntArray(DROP_CAP)
    val dropValue = FloatArray(DROP_CAP)
    var dropCount = 0
        private set
    private val recSorted = IntArray(RECORD_CAP)
    private val keyCount = IntArray(KEYS)
    private val keyStart = IntArray(KEYS)

    fun beginFrame() {
        dropCount = 0
        volumeCount = 0
        pagesUsed = 0
        runCount = 0
    }

    fun volumeAt(i: Int): JetVolume = volumes[i]

    /** Bins the lines of [kind] in [frame] for the volume box; null when there are none or the volume list is full. */
    @Suppress("LongParameterList", "LongMethod") // The box of a volume; count, bin and fill passes in one place.
    fun bind(kind: GasKind, frame: Long, minX: Int, minY: Int, minZ: Int, w: Int, h: Int, d: Int): JetVolume? {
        var n = 0
        for (l in 0 until GasJetLines.count) {
            val line = GasJetLines.lines[l]
            if (line.kind == kind && line.frame == frame) lineIdx[n++] = l
        }
        if (n == 0 || volumeCount >= MAX_VOLUMES) return null
        val vol = volumes[volumeCount++]
        vol.kind = kind; vol.frame = frame
        vol.minX = minX; vol.minY = minY; vol.minZ = minZ; vol.w = w; vol.h = h; vol.d = d
        for (i in 0 until vol.table.capacity()) vol.table.put(i, 0)
        var records = 0
        val firstPage = pagesUsed
        for (k in 0 until n) {
            val line = GasJetLines.lines[lineIdx[k]]
            writeSamples(line, minX, minY, minZ)
            records = binLine(vol, line, minX, minY, minZ, w, h, d, records)
        }
        // Count, prefix over the pages of this volume, fill, then keep the best runs of each tile.
        for (i in 0 until records) keyCount[recKey[i]]++
        var sum = 0
        for (p in firstPage until pagesUsed) for (t in 0 until GasJetLines.PAGE_TILES) {
            val key = p * GasJetLines.PAGE_TILES + t
            keyStart[key] = sum
            sum += keyCount[key]
        }
        for (i in 0 until records) recSorted[keyStart[recKey[i]]++] = i
        for (p in firstPage until pagesUsed) for (t in 0 until GasJetLines.PAGE_TILES) {
            val key = p * GasJetLines.PAGE_TILES + t
            val cnt = keyCount[key]
            if (cnt == 0) continue
            keyCount[key] = 0
            val begin = keyStart[key] - cnt
            val take = min(cnt, GasJetLines.RUNS_PER_TILE)
            if (runCount + take > GasJetLines.RUN_CAP) {
                for (m in 0 until cnt) dropTile(vol, key, recSplat[recSorted[begin + m]])
                continue
            }
            val offset = runCount
            for (j in 0 until take) {
                var best = -1
                var bestScore = -1f
                for (m in 0 until cnt) {
                    val rec = recSorted[begin + m]
                    if (recScore[rec] > bestScore) { bestScore = recScore[rec]; best = rec }
                }
                runTexels.put(runCount++, recRun[best])
                recScore[best] = -1f
                recSplat[best] = -1f
            }
            // The runs past the best 8 are not drawn as capsules; the field splat shows them.
            for (m in 0 until cnt) {
                val rec = recSorted[begin + m]
                if (recScore[rec] >= 0f) dropTile(vol, key, recSplat[rec])
            }
            pageData[key] = offset or (take shl 24)
        }
        return vol
    }

    /** A block offset from the volume box as unsigned 1/256 block, with a 64 block bias for the margin below the box. */
    private fun fixed(offset: Float): Int = ((offset + FIXED_BIAS) * FIXED_SCALE).toInt().coerceIn(0, 65535)

    private fun writeSamples(line: GasJetLines.Line, minX: Int, minY: Int, minZ: Int) {
        for (i in 0 until line.n) {
            val g = line.first + i
            val o = g * 4
            // GLSL 150 has no bit casts, so every field is fixed point: x and y in word 0, z and radius in word 1, c in word 2.
            sampleTexels.put(o, fixed(line.ox - minX + GasJetLines.x[g]) or (fixed(line.oy - minY + GasJetLines.y[g]) shl 16))
            val rq = (GasJetLines.r[g] / 2f * 65535f).toInt().coerceIn(0, 65535)
            sampleTexels.put(o + 1, fixed(line.oz - minZ + GasJetLines.z[g]) or (rq shl 16))
            sampleTexels.put(o + 2, (GasJetLines.c[g] * 65535f).toInt().coerceIn(0, 65535))
            sampleTexels.put(o + 3, 0)
        }
    }

    /** Marks the tiles each sample reaches, then adds one record for each tile the line crosses. Returns the new record count. */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // One line over its tile box.
    private fun binLine(vol: JetVolume, line: GasJetLines.Line, minX: Int, minY: Int, minZ: Int, w: Int, h: Int, d: Int, start: Int): Int {
        var lx = Int.MAX_VALUE; var ly = Int.MAX_VALUE; var lz = Int.MAX_VALUE
        var hx = Int.MIN_VALUE; var hy = Int.MIN_VALUE; var hz = Int.MIN_VALUE
        val ax = line.ox - minX; val ay = line.oy - minY; val az = line.oz - minZ
        for (i in 0 until line.n) {
            val g = line.first + i
            val reach = GasJetLines.r[g] + REACH
            lx = min(lx, floor(ax + GasJetLines.x[g] - reach).toInt()); hx = max(hx, floor(ax + GasJetLines.x[g] + reach).toInt())
            ly = min(ly, floor(ay + GasJetLines.y[g] - reach).toInt()); hy = max(hy, floor(ay + GasJetLines.y[g] + reach).toInt())
            lz = min(lz, floor(az + GasJetLines.z[g] - reach).toInt()); hz = max(hz, floor(az + GasJetLines.z[g] + reach).toInt())
        }
        lx = max(lx, 0); ly = max(ly, 0); lz = max(lz, 0)
        hx = minOf(hx, w - 1, lx + LINE_AXIS - 1); hy = minOf(hy, h - 1, ly + LINE_AXIS - 1); hz = minOf(hz, d - 1, lz + LINE_AXIS - 1)
        if (hx < lx || hy < ly || hz < lz) return start
        val ex = hx - lx + 1; val ey = hy - ly + 1; val ez = hz - lz + 1
        val total = ex * ey * ez
        for (i in 0 until total) { lineLo[i] = Int.MAX_VALUE; lineHi[i] = -1; lineC[i] = 0f; lineR[i] = 0f }
        for (i in 0 until line.n) {
            val g = line.first + i
            val px = ax + GasJetLines.x[g]; val py = ay + GasJetLines.y[g]; val pz = az + GasJetLines.z[g]
            val reach = GasJetLines.r[g] + REACH
            val reachSq = reach * reach
            val x0 = max(lx, floor(px - reach).toInt()); val x1 = min(hx, floor(px + reach).toInt())
            val y0 = max(ly, floor(py - reach).toInt()); val y1 = min(hy, floor(py + reach).toInt())
            val z0 = max(lz, floor(pz - reach).toInt()); val z1 = min(hz, floor(pz + reach).toInt())
            for (tz in z0..z1) for (ty in y0..y1) for (tx in x0..x1) {
                val dx = tx + 0.5 - px; val dy = ty + 0.5 - py; val dz = tz + 0.5 - pz
                if (dx * dx + dy * dy + dz * dz > reachSq) continue
                val t = (tx - lx) + ex * ((ty - ly) + ey * (tz - lz))
                if (i < lineLo[t]) lineLo[t] = i
                if (i > lineHi[t]) lineHi[t] = i
                if (GasJetLines.c[g] > lineC[t]) { lineC[t] = GasJetLines.c[g]; lineR[t] = GasJetLines.r[g] }
            }
        }
        var records = start
        for (tz in lz..hz) for (ty in ly..hy) for (tx in lx..hx) {
            val t = (tx - lx) + ex * ((ty - ly) + ey * (tz - lz))
            if (lineHi[t] < 0) continue
            val splat = lineC[t] * min(1f, Math.PI.toFloat() * lineR[t] * lineR[t] / 3f)
            if (records >= RECORD_CAP) { dropCell(vol, minX + tx, minY + ty, minZ + tz, splat); continue }
            val page = pageFor(vol, tx shr 3, ty shr 3, tz shr 3)
            if (page < 0) { dropCell(vol, minX + tx, minY + ty, minZ + tz, splat); continue }
            val local = (tx and 7) + 8 * ((ty and 7) + 8 * (tz and 7))
            val count = min(lineHi[t] - lineLo[t] + 1, 63)
            recKey[records] = page * GasJetLines.PAGE_TILES + local
            recRun[records] = (line.first + lineLo[t]) or (count shl 16)
            recScore[records] = lineC[t]
            recSplat[records] = splat
            records++
        }
        return records
    }

    /** The page of the cell, allocated from the shared atlas on first use; -1 when the atlas is full or the cell is off the table. */
    private fun pageFor(vol: JetVolume, cx: Int, cy: Int, cz: Int): Int {
        if (cx !in 0 until GasJetLines.TABLE_X || cy !in 0 until GasJetLines.TABLE_Y || cz !in 0 until GasJetLines.TABLE_Z) return -1
        val ti = cx + GasJetLines.TABLE_X * (cy + GasJetLines.TABLE_Y * cz)
        val have = vol.table.get(ti).toInt() and 0xFFFF
        if (have != 0) return have - 1
        if (pagesUsed >= GasJetLines.ATLAS_PAGES) {
            if (!atlasFullLogged) {
                atlasFullLogged = true
                log.warn("Gas jet tile atlas is full ({} pages); tiles beyond it are drawn only by the 1-block field splat", GasJetLines.ATLAS_PAGES)
            }
            return -1
        }
        val page = pagesUsed++
        pageCx[page] = cx; pageCy[page] = cy; pageCz[page] = cz
        val base = page * GasJetLines.PAGE_TILES
        for (i in 0 until GasJetLines.PAGE_TILES) pageData[base + i] = 0
        vol.table.put(ti, (page + 1).toShort())
        return page
    }

    val volumesInUse: Int get() = volumeCount

    private fun dropCell(vol: JetVolume, x: Int, y: Int, z: Int, value: Float) {
        if (!GasJetLines.OVERFLOW_SPLAT || dropCount >= DROP_CAP || value <= 0f) return
        dropKind[dropCount] = vol.kind.ordinal; dropFrame[dropCount] = vol.frame
        dropX[dropCount] = x; dropY[dropCount] = y; dropZ[dropCount] = z; dropValue[dropCount] = value
        dropCount++
    }

    /** A tile of the atlas page [key] / 512 that lost a run; the tile position comes from the page cell. */
    private fun dropTile(vol: JetVolume, key: Int, value: Float) {
        val page = key / GasJetLines.PAGE_TILES
        val local = key % GasJetLines.PAGE_TILES
        val x = vol.minX + pageCx[page] * 8 + (local and 7)
        val y = vol.minY + pageCy[page] * 8 + ((local shr 3) and 7)
        val z = vol.minZ + pageCz[page] * 8 + (local shr 6)
        dropCell(vol, x, y, z, value)
    }

    /** The tile entries of page [page], for the upload; the buffer is reused by the next call. */
    fun pageContents(page: Int): java.nio.IntBuffer {
        val base = page * GasJetLines.PAGE_TILES
        for (i in 0 until GasJetLines.PAGE_TILES) pageBuffer.put(i, pageData[base + i])
        pageBuffer.position(0).limit(GasJetLines.PAGE_TILES)
        return pageBuffer
    }
}
