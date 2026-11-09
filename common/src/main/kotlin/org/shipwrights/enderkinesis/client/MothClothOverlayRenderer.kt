package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongArrays
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.RandomSource
import org.joml.Matrix3f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.MothClothBlock
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * Draws the Moth Cloth glint over the base wool. Each 16³ section keeps a baked mesh in section-local
 * coordinates; it is rebuilt only when its block set, block states or light change (LevelRenderer.setSectionDirty).
 */
@OptIn(GameTickOnly::class)
object MothClothOverlayRenderer {

    private val random: RandomSource = RandomSource.create()

    /** Must keep moth_cloth.fsh's GLINT_CELL_PERIOD seamless across the wrap. */
    private const val WORLD_WRAP: Double = 2000.0

    private const val BUILDER_BYTES = 1 shl 16

    private fun wrap(coord: Double): Double = coord - Math.floor(coord / WORLD_WRAP) * WORLD_WRAP

    private class Section {
        val buffer = VertexBuffer(VertexBuffer.Usage.STATIC)
        var positions = LongArrayList()
        var next = LongArrayList()
        var dirty = true
        var empty = true
    }

    // Render thread only, except dirtyKeys and allDirty, which the chunk and light paths may also write.
    private val sections = Long2ObjectOpenHashMap<Section>()
    private val dirtyKeys = LongOpenHashSet()
    @Volatile private var allDirty = false
    @Volatile private var tracking = false
    private var seenVersion = -1
    private val snapshot = LongArrayList()
    private val gone = LongArrayList()
    private var builder: BufferBuilder? = null
    private val buildPose = PoseStack()
    private val cursor = BlockPos.MutableBlockPos()
    private val origin = Vector3d()
    private val shipLinear = Matrix3f()
    private val identity = Matrix3f()

    /** Block or light change in a section; the glint mesh there and next to it bakes the old AO and light. */
    @JvmStatic
    fun onSectionDirty(x: Int, y: Int, z: Int) {
        if (!tracking) return
        synchronized(dirtyKeys) { dirtyKeys.add(SectionPos.asLong(x, y, z)) }
    }

    /** Resource reload or a graphics option change: the atlas UVs or AO in every mesh may be stale. */
    @JvmStatic
    fun onAllChanged() {
        allDirty = true
    }

    @JvmStatic
    fun render(
        poseStack: PoseStack,
        camX: Double,
        camY: Double,
        camZ: Double,
    ) {
        val mc = Minecraft.getInstance()
        val level: ClientLevel = mc.level ?: return
        // Ship chunks bypass the chunk-load scan mixin (VS2 cancels
        // replaceWithPacketData for shipyard chunks) — rescan them here.
        // Internally rate-limited to once per 2 s.
        MothClothClientPositions.rescanShips(level)
        regroup()
        markDirty()
        if (sections.isEmpty()) return

        val shader = MothClothRenderType.shader ?: return
        val dirty = sections.long2ObjectEntrySet().fastIterator()
        while (dirty.hasNext()) {
            val e = dirty.next()
            if (e.value.dirty) build(level, e.value, e.longKey)
        }

        // Wrapped: floats lose sub-block precision far from the origin, and the glint repeats over this span.
        shader.getUniform("CameraPos")
            ?.set(wrap(camX).toFloat(), wrap(camY).toFloat(), wrap(camZ).toFloat())
        shader.getUniform("WallTime")
            ?.set(((System.currentTimeMillis() % 1000000L) / 1000.0).toFloat())
        val shipMat = shader.getUniform("ShipMat")

        // The shader wants camera-relative positions and only the camera rotation in ModelViewMat,
        // which is identity during renderLevel (vanilla rotates entity vertices on the CPU).
        val mvStack = RenderSystem.getModelViewStack()
        mvStack.pushPose()
        mvStack.mulPoseMatrix(poseStack.last().pose())
        RenderSystem.applyModelViewMatrix()
        MothClothRenderType.TYPE.setupRenderState()
        try {
            val modelView = RenderSystem.getModelViewMatrix()
            val projection = RenderSystem.getProjectionMatrix()
            val it = sections.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                val s = e.value
                if (s.empty) continue
                val key = e.longKey
                val ox = SectionPos.sectionToBlockCoord(SectionPos.x(key))
                val oy = SectionPos.sectionToBlockCoord(SectionPos.y(key))
                val oz = SectionPos.sectionToBlockCoord(SectionPos.z(key))
                val ship = level.getLoadedShipManagingPos(SectionPos.x(key), SectionPos.z(key)) as? ClientShip
                // Cloaked ships render into the concealment side FB; the glint must not stay in the open air.
                if (ship != null && ClientShipCloakingState.getProgress(ship.id) > 0.001f) continue
                if (ship != null) {
                    // shipToWorld's translation is shipyard-scale (float ulp ≈ 2 blocks): keep it in doubles,
                    // send only the camera-relative origin and the 3×3 rotation/scale as floats.
                    val s2w = ship.renderTransform.shipToWorld
                    s2w.transformPosition(origin.set(ox.toDouble(), oy.toDouble(), oz.toDouble()))
                    shipLinear.set(
                        s2w.m00().toFloat(), s2w.m01().toFloat(), s2w.m02().toFloat(),
                        s2w.m10().toFloat(), s2w.m11().toFloat(), s2w.m12().toFloat(),
                        s2w.m20().toFloat(), s2w.m21().toFloat(), s2w.m22().toFloat(),
                    )
                    shipMat?.set(shipLinear)
                } else {
                    origin.set(ox.toDouble(), oy.toDouble(), oz.toDouble())
                    shipMat?.set(identity)
                }
                shader.CHUNK_OFFSET?.set((origin.x - camX).toFloat(), (origin.y - camY).toFloat(), (origin.z - camZ).toFloat())
                s.buffer.bind()
                s.buffer.drawWithShader(modelView, projection, shader)
            }
            VertexBuffer.unbind()
        } finally {
            shader.CHUNK_OFFSET?.set(0f, 0f, 0f)
            shipMat?.set(identity)
            MothClothRenderType.TYPE.clearRenderState()
            mvStack.popPose()
            RenderSystem.applyModelViewMatrix()
        }
    }

    /** Sorts the position set into sections when it changed; a section whose members changed rebuilds. */
    private fun regroup() {
        val version = MothClothClientPositions.version
        if (version == seenVersion) return
        seenVersion = MothClothClientPositions.snapshotInto(snapshot)
        for (s in sections.values) s.next.clear()
        for (n in 0 until snapshot.size) {
            val p = snapshot.getLong(n)
            val key = SectionPos.asLong(BlockPos.getX(p) shr 4, BlockPos.getY(p) shr 4, BlockPos.getZ(p) shr 4)
            var s = sections.get(key)
            if (s == null) {
                s = Section()
                sections.put(key, s)
            }
            s.next.add(p)
        }
        gone.clear()
        val it = sections.long2ObjectEntrySet().fastIterator()
        while (it.hasNext()) {
            val e = it.next()
            val s = e.value
            if (s.next.isEmpty()) {
                gone.add(e.longKey)
                continue
            }
            LongArrays.unstableSort(s.next.elements(), 0, s.next.size)
            if (s.next != s.positions) s.dirty = true
            val swap = s.positions
            s.positions = s.next
            s.next = swap
        }
        for (n in 0 until gone.size) sections.remove(gone.getLong(n))?.buffer?.close()
        tracking = !sections.isEmpty()
    }

    private fun markDirty() {
        if (allDirty) {
            allDirty = false
            for (s in sections.values) s.dirty = true
        }
        synchronized(dirtyKeys) {
            if (dirtyKeys.isEmpty()) return
            val it = dirtyKeys.iterator()
            while (it.hasNext()) sections.get(it.nextLong())?.dirty = true
            dirtyKeys.clear()
        }
    }

    private fun build(level: ClientLevel, s: Section, key: Long) {
        s.dirty = false
        val ox = SectionPos.sectionToBlockCoord(SectionPos.x(key))
        val oy = SectionPos.sectionToBlockCoord(SectionPos.y(key))
        val oz = SectionPos.sectionToBlockCoord(SectionPos.z(key))
        val out = builder ?: BufferBuilder(BUILDER_BYTES).also { builder = it }
        val blockRenderer = Minecraft.getInstance().blockRenderer
        out.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        for (n in 0 until s.positions.size) {
            cursor.set(s.positions.getLong(n))
            val state = level.getBlockState(cursor)
            if (state.block !is MothClothBlock) continue
            buildPose.pushPose()
            buildPose.translate((cursor.x - ox).toFloat(), (cursor.y - oy).toFloat(), (cursor.z - oz).toFloat())
            blockRenderer.renderBatched(state, cursor, level, buildPose, out, true, random)
            buildPose.popPose()
        }
        val rendered = out.end()
        s.empty = rendered.isEmpty()
        if (s.empty) {
            rendered.release()
            return
        }
        s.buffer.bind()
        s.buffer.upload(rendered)
        VertexBuffer.unbind()
    }
}
