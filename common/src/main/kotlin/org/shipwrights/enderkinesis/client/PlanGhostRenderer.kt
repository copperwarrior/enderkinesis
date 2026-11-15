package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.floats.FloatArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.ClientDraftingTables
import org.shipwrights.enderkinesis.construction.BuildCellStatus
import org.shipwrights.enderkinesis.construction.ClientBuildJob
import org.shipwrights.enderkinesis.construction.ClientBuildJobs
import org.shipwrights.enderkinesis.construction.ClientPlans
import org.shipwrights.enderkinesis.construction.PLAN_SECTION_CELLS
import org.shipwrights.enderkinesis.construction.PLAN_SECTION_EDGE
import org.shipwrights.enderkinesis.construction.PlanAnchor
import org.shipwrights.enderkinesis.construction.PlanRef
import org.shipwrights.enderkinesis.construction.PlanSection
import org.shipwrights.enderkinesis.construction.PlanSectionState
import org.shipwrights.enderkinesis.construction.PlanSections
import org.shipwrights.enderkinesis.construction.cellWorldState
import org.shipwrights.enderkinesis.construction.planCellLocal
import org.shipwrights.enderkinesis.construction.planCellLocalX
import org.shipwrights.enderkinesis.construction.planCellLocalY
import org.shipwrights.enderkinesis.construction.planCellLocalZ
import org.shipwrights.enderkinesis.item.heldCommandStaff
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Translucent plan ghosts of the loaded drafting tables (an open table screen shows its pending placement). Drawn after translucent
 * terrain with no depth write, so water behind it stays. One VBO for each plan section near the camera and in the frustum; a VBO is rebuilt
 * only when the build progress of its section changes, and the least recently drawn ones go past a byte budget.
 */
object PlanGhostRenderer {

    /** One mesh per placed plan, so two tables never share a near list or job marks. Mutable only as the lookup scratch. */
    private class MeshKey(var hash: String, var rotation: Rotation, var mirror: Mirror, var anchor: Long) {
        fun set(hash: String, rotation: Rotation, mirror: Mirror, anchor: Long): MeshKey = also {
            it.hash = hash
            it.rotation = rotation
            it.mirror = mirror
            it.anchor = anchor
        }

        fun copy() = MeshKey(hash, rotation, mirror, anchor)

        override fun equals(other: Any?): Boolean = other is MeshKey &&
            anchor == other.anchor && rotation == other.rotation && mirror == other.mirror && hash == other.hash

        override fun hashCode(): Int = ((hash.hashCode() * HASH_STEP + rotation.ordinal) * HASH_STEP + mirror.ordinal) * HASH_STEP +
            anchor.hashCode()
    }

    private class GpuSection(val mesh: GhostMesh, val index: Int) {
        var buffer: VertexBuffer? = null
        var marks: VertexBuffer? = null
        var version = UNBUILT

        // Of both buffers, for the byte budget.
        var bytes = 0L
        var lastDrawn = 0L

        // World box of the section, relative to the anchor; the mesh vertices are relative to its minimum corner.
        var boxX = 0
        var boxY = 0
        var boxZ = 0
    }

    // A hash names fixed content, so a newer PlanSections object for the same hash keeps the built sections.
    private class GhostMesh(val key: MeshKey, var sections: PlanSections) {
        val axes = axesOf(key.rotation, key.mirror)
        val grid = GhostSectionGrid(sections.size.x, sections.size.y, sections.size.z, axes)
        val faceSteps = axes.localFaceSteps()

        // With a job, plan-air cells can hold red marks, so empty sections count too. The near list is
        // collected again when this changes.
        var withJob = false
        val nearFilter = SectionFilter { sx, sy, sz -> withJob || this.sections.state(sx, sy, sz) != PlanSectionState.EMPTY }
        val gpu = Int2ObjectOpenHashMap<GpuSection>()
        var nearX = Int.MIN_VALUE
        var nearY = Int.MIN_VALUE
        var nearZ = Int.MIN_VALUE
        var nearRadius = 0
    }

    // Render thread only.
    private val meshes = LinkedHashMap<MeshKey, GhostMesh>(MAX_MESHES + 1, LOAD_FACTOR, true)
    private val live = ArrayList<GpuSection>()
    private var liveBytes = 0L
    private val builder by lazy(LazyThreadSafetyMode.NONE) { BufferBuilder(BUILDER_BYTES) }
    private val markBuilder by lazy(LazyThreadSafetyMode.NONE) { BufferBuilder(MARK_BUILDER_BYTES) }
    private val cellPose = PoseStack()
    private val modelView = Matrix4f()
    private val viewProjection = Matrix4f()
    private val ghostBase = Matrix4f()
    private val shipTurn = Matrix4f()
    private val camLocal = Vector3d()
    private val frustum = FrustumIntersection()
    private val box = IntArray(BOX_INTS)
    private val visible = IntArrayList()
    private val visibleFade = FloatArrayList()
    private val scratchKey = MeshKey("", Rotation.NONE, Mirror.NONE, 0L)
    private val neighbours = arrayOfNulls<PlanSection>(NEIGHBOURS)
    private val modelRandom = RandomSource.create()
    private var frame = 0L

    @Suppress("UNUSED_PARAMETER") // partialTick: same signature as the other after-translucent renderers.
    fun renderAll(
        poseStack: PoseStack, consumers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, partialTick: Float,
    ) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        player.heldCommandStaff()?.let {
            CaptureBoxRenderer.render(poseStack, consumers, level, StaffBuildClient.view(it), camX, camY, camZ)
        }
        frame++
        val preview = DraftingTableClient.preview
        val tables = ClientDraftingTables.loaded
        // Backwards, so a table of a gone level or chunk leaves the list here.
        for (i in tables.size - 1 downTo 0) {
            val table = tables[i]
            if (table.isRemoved || table.level !== level) {
                tables.removeAt(i)
                continue
            }
            // The open screen shows its pending placement in place of the table's own.
            val own = if (preview != null && preview.table == table.blockPos) preview else null
            val plan = own?.plan ?: table.plan ?: continue
            val anchor = own?.anchor ?: table.anchor() ?: continue
            drawGhost(poseStack, level, table.blockPos, plan, anchor, jobAt(plan, anchor), camX, camY, camZ)
        }
        evict()
    }

    /**
     * One plan ghost. The [table] owns the frame: on a ship the ghost draws in that ship's frame (the camera goes into
     * ship space and the ship's turn and scale go into the pose), so the plan moves with the ship.
     */
    @Suppress("LongParameterList") // Camera-relative doubles keep the ghost exact far from the world origin.
    private fun drawGhost(
        poseStack: PoseStack, level: Level, table: BlockPos, plan: PlanRef, anchor: PlanAnchor, job: ClientBuildJob?,
        camX: Double, camY: Double, camZ: Double,
    ) {
        val pos = anchor.pos
        camLocal.set(camX, camY, camZ)
        val ship = if (level.isBlockInShipyard(table)) level.getShipManagingPos(table) as? ClientShip ?: return else null
        ship?.renderTransform?.worldToShip?.transformPosition(camLocal)
        // Ship space is in ship blocks; the show distance is in world blocks.
        val scale = ship?.renderTransform?.shipToWorldScaling?.x()?.takeIf { it > 0.0 } ?: 1.0
        val showLocal = SHOW_DISTANCE / scale
        // A cheap bound first: no plan cell lies farther from the anchor than the sum of the plan's sides.
        val size = plan.size
        val reach = showLocal + size.x + size.y + size.z
        if (camLocal.distanceSquared(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()) > reach * reach) return
        ghostBase.set(poseStack.last().pose())
        ship?.let { ghostBase.mul(shipTurn.set(it.renderTransform.shipToWorld).setTranslation(0f, 0f, 0f)) }
        val sections = ClientPlans.sections(plan) ?: return
        val mesh = meshFor(scratchKey.set(plan.hash, anchor.rotation, anchor.mirror, pos.asLong()), sections)
        if (mesh.withJob != (job != null)) {
            mesh.withJob = job != null
            mesh.nearX = Int.MIN_VALUE
        }
        val cx = camLocal.x - pos.x
        val cy = camLocal.y - pos.y
        val cz = camLocal.z - pos.z
        collectNear(mesh, cx, cy, cz, ceil(showLocal).toInt().coerceIn(1, GhostSectionGrid.MAX_RADIUS))
        viewProjection.set(RenderSystem.getProjectionMatrix()).mul(ghostBase)
        frustum.set(viewProjection, false)
        update(mesh, job, cx, cy, cz, scale)
        draw(mesh, ghostBase, -cx, -cy, -cz)
    }

    private fun meshFor(key: MeshKey, sections: PlanSections): GhostMesh {
        meshes[key]?.let {
            it.sections = sections
            return it
        }
        val mesh = GhostMesh(key.copy(), sections)
        meshes[mesh.key] = mesh
        if (meshes.size > MAX_MESHES) drop(meshes.values.first())
        return mesh
    }

    private fun drop(mesh: GhostMesh) {
        meshes.remove(mesh.key)
        for (gpu in mesh.gpu.values) free(gpu)
        mesh.gpu.clear()
    }

    /** The near list changes only when the camera enters another section, so the frame cost does not grow with the plan. */
    private fun collectNear(mesh: GhostMesh, cx: Double, cy: Double, cz: Double, radius: Int) {
        val sx = Math.floorDiv(floor(cx).toInt(), PLAN_SECTION_EDGE)
        val sy = Math.floorDiv(floor(cy).toInt(), PLAN_SECTION_EDGE)
        val sz = Math.floorDiv(floor(cz).toInt(), PLAN_SECTION_EDGE)
        if (sx == mesh.nearX && sy == mesh.nearY && sz == mesh.nearZ && radius == mesh.nearRadius) return
        mesh.nearX = sx
        mesh.nearY = sy
        mesh.nearZ = sz
        mesh.nearRadius = radius
        // A section boundary away from the camera section adds one section of slack.
        mesh.grid.collectNear(cx, cy, cz, minOf(radius + PLAN_SECTION_EDGE, GhostSectionGrid.MAX_RADIUS), box, mesh.nearFilter)
    }

    /** Builds the nearest stale visible sections within a budget, asks for missing ones, and lists what to draw. */
    @Suppress("LongParameterList") // A mesh, its job, the camera offset and the ship scale.
    private fun update(mesh: GhostMesh, job: ClientBuildJob?, cx: Double, cy: Double, cz: Double, scale: Double) {
        val sections = mesh.sections
        val planGrid = sections.grid
        val deadline = System.nanoTime() + BUILD_NANOS
        var builds = 0
        var asks = 0
        visible.clear()
        visibleFade.clear()
        for (i in 0 until mesh.grid.nearCount) {
            val sx = mesh.grid.nearX(i)
            val sy = mesh.grid.nearY(i)
            val sz = mesh.grid.nearZ(i)
            val index = planGrid.index(sx, sy, sz)
            if (job != null && job.isSectionDone(sx, sy, sz)) {
                mesh.gpu.remove(index)?.let(::free)
                continue
            }
            mesh.grid.worldBox(sx, sy, sz, box)
            val fade = ghostFade(GhostSectionGrid.boxDistanceSq(box, cx, cy, cz) * scale * scale)
            if (fade <= 0f) continue
            // Before state(): on the client it reads the shared, synchronized section cache.
            if (!GhostSectionGrid.inFrustum(frustum, box, cx, cy, cz)) continue
            val version = job?.sectionVersion(sx, sy, sz) ?: NO_JOB
            var gpu = mesh.gpu.get(index)
            if (gpu == null || gpu.version != version) {
                val canBuild = builds < MAX_BUILDS_PER_FRAME && System.nanoTime() < deadline
                val state = sections.state(index)
                val empty = state == PlanSectionState.EMPTY
                // A miss asks the server for the section; nearest first, as the near list is sorted.
                val ask = if (state == PlanSectionState.LOADING) asks++ < MAX_ASKS_PER_FRAME else canBuild
                val data = if (ask && !empty) sections.sectionAt(index) else null
                // An empty section with no status from the server has nothing to mark, so it spends no build.
                val marksOnly = empty && job != null && version != 0
                if (canBuild && (data != null || marksOnly)) {
                    gpu = gpu ?: GpuSection(mesh, index).also { mesh.gpu.put(index, it) }
                    rebuild(gpu, mesh, data, sx, sy, sz, job)
                    gpu.version = version
                    builds++
                }
            }
            if (gpu != null && (gpu.buffer != null || gpu.marks != null)) {
                visible.add(index)
                visibleFade.add(fade)
            }
        }
    }

    @Suppress("LongParameterList") // A section, its place in the grid and its build progress.
    private fun rebuild(gpu: GpuSection, mesh: GhostMesh, data: PlanSection?, sx: Int, sy: Int, sz: Int, job: ClientBuildJob?) {
        val mc = Minecraft.getInstance()
        val shaper = mc.blockRenderer.blockModelShaper
        val origin = PlanAnchor(Level.OVERWORLD, BlockPos.ZERO, mesh.key.rotation, mesh.key.mirror)
        val axes = mesh.axes
        mesh.grid.worldBox(sx, sy, sz, box)
        gpu.boxX = box[0]
        gpu.boxY = box[1]
        gpu.boxZ = box[2]
        val baseX = sx * PLAN_SECTION_EDGE
        val baseY = sy * PLAN_SECTION_EDGE
        val baseZ = sz * PLAN_SECTION_EDGE
        loadNeighbours(mesh.sections, sx, sy, sz)
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        data?.forEachNonAir { lx, ly, lz, planState ->
            if (planState.renderShape != RenderShape.MODEL) return@forEachNonAir
            val status = job?.status(sx, sy, sz, planCellLocal(lx, ly, lz)) ?: BuildCellStatus.TODO
            if (status == BuildCellStatus.DONE) return@forEachNonAir
            val state = origin.cellWorldState(planState)
            val px = baseX + lx
            val py = baseY + ly
            val pz = baseZ + lz
            val tint = mc.blockColors.getColor(state, null, null, 0)
            val r = if (tint == -1) 1f else (tint shr 16 and 0xFF) / 255f
            val g = if (tint == -1) 1f else (tint shr 8 and 0xFF) / 255f
            val b = if (tint == -1) 1f else (tint and 0xFF) / 255f
            val flagged = status == BuildCellStatus.BLOCKED || status == BuildCellStatus.MISSING
            val hidden = if (!hidesSharedFaces(planState)) 0 else ghostHiddenFaces(lx, ly, lz, mesh.faceSteps) { x, y, z -> solidNear(data, x, y, z) }
            cellPose.pushPose()
            cellPose.translate(
                (axes.worldX(px, py, pz) - gpu.boxX).toFloat(),
                (axes.worldY(px, py, pz) - gpu.boxY).toFloat(),
                (axes.worldZ(px, py, pz) - gpu.boxZ).toFloat(),
            )
            renderModel(cellPose.last(), state, shaper.getBlockModel(state), r, g, b, hidden, flagged)
            cellPose.popPose()
        }
        neighbours.fill(null)
        markBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        if (job != null) markAirCells(gpu, mesh, data, sx, sy, sz, job)
        val blocks = builder.endOrDiscardIfEmpty()
        val marks = markBuilder.endOrDiscardIfEmpty()
        if (blocks == null && marks == null) {
            release(gpu)
            return
        }
        val wasLive = gpu.buffer != null || gpu.marks != null
        val bytes = (blocks?.drawState()?.vertexBufferSize() ?: 0).toLong() + (marks?.drawState()?.vertexBufferSize() ?: 0)
        gpu.buffer = upload(gpu.buffer, blocks)
        gpu.marks = upload(gpu.marks, marks)
        if (!wasLive) live.add(gpu)
        liveBytes += bytes - gpu.bytes
        gpu.bytes = bytes
        gpu.lastDrawn = frame
    }

    /** Uploads [rendered] into [buffer] (made when null); an empty render frees the buffer and returns null. */
    private fun upload(buffer: VertexBuffer?, rendered: BufferBuilder.RenderedBuffer?): VertexBuffer? {
        if (rendered == null) {
            buffer?.close()
            return null
        }
        val out = buffer ?: VertexBuffer(VertexBuffer.Usage.STATIC)
        out.bind()
        out.upload(rendered)
        VertexBuffer.unbind()
        return out
    }

    /**
     * A red box over each plan-air cell that the job cannot clear (BLOCKED or MISSING), for example a full chest in
     * the way. Plan-air cells that wait for nothing stay invisible.
     */
    @Suppress("LongParameterList") // A section, its place in the grid and its build progress.
    private fun markAirCells(gpu: GpuSection, mesh: GhostMesh, data: PlanSection?, sx: Int, sy: Int, sz: Int, job: ClientBuildJob) {
        // Version 0: the server never sent a status for this section, so every cell is TODO.
        if (job.sectionVersion(sx, sy, sz) == 0) return
        val size = mesh.sections.size
        val axes = mesh.axes
        for (local in 0 until PLAN_SECTION_CELLS) {
            val status = job.status(sx, sy, sz, local)
            if (status != BuildCellStatus.BLOCKED && status != BuildCellStatus.MISSING) continue
            val lx = planCellLocalX(local)
            val ly = planCellLocalY(local)
            val lz = planCellLocalZ(local)
            if (data?.stateAt(lx, ly, lz) != null) continue
            val px = sx * PLAN_SECTION_EDGE + lx
            val py = sy * PLAN_SECTION_EDGE + ly
            val pz = sz * PLAN_SECTION_EDGE + lz
            if (px >= size.x || py >= size.y || pz >= size.z) continue
            markBox(
                (axes.worldX(px, py, pz) - gpu.boxX).toFloat(),
                (axes.worldY(px, py, pz) - gpu.boxY).toFloat(),
                (axes.worldZ(px, py, pz) - gpu.boxZ).toFloat(),
            )
        }
    }

    /** Six untextured faces of the cell at ([x], [y], [z]), a little larger than the block so its faces do not z-fight. */
    private fun markBox(x: Float, y: Float, z: Float) {
        val x0 = x - MARK_GROW
        val y0 = y - MARK_GROW
        val z0 = z - MARK_GROW
        val x1 = x + 1f + MARK_GROW
        val y1 = y + 1f + MARK_GROW
        val z1 = z + 1f + MARK_GROW
        markQuad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
        markQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0)
        markQuad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0)
        markQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        markQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0)
        markQuad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1)
    }

    @Suppress("LongParameterList") // Four corners.
    private fun markQuad(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
    ) {
        markBuilder.vertex(ax.toDouble(), ay.toDouble(), az.toDouble()).color(1f, FLAG_DIM, FLAG_DIM, MARK_ALPHA).endVertex()
        markBuilder.vertex(bx.toDouble(), by.toDouble(), bz.toDouble()).color(1f, FLAG_DIM, FLAG_DIM, MARK_ALPHA).endVertex()
        markBuilder.vertex(cx.toDouble(), cy.toDouble(), cz.toDouble()).color(1f, FLAG_DIM, FLAG_DIM, MARK_ALPHA).endVertex()
        markBuilder.vertex(dx.toDouble(), dy.toDouble(), dz.toDouble()).color(1f, FLAG_DIM, FLAG_DIM, MARK_ALPHA).endVertex()
    }

    /** The six plan-local face neighbours of a section into [neighbours]. */
    private fun loadNeighbours(sections: PlanSections, sx: Int, sy: Int, sz: Int) {
        val grid = sections.grid
        for (axis in 0 until PlanAxes.AXES) {
            for (sign in 0..1) {
                val step = if (sign == 0) -1 else 1
                val nx = sx + if (axis == 0) step else 0
                val ny = sy + if (axis == 1) step else 0
                val nz = sz + if (axis == 2) step else 0
                val slot = axis * 2 + sign
                // Only a section already here: a loading neighbour leaves its shared faces drawn, and asks for nothing.
                neighbours[slot] = if (!grid.contains(nx, ny, nz) || sections.state(nx, ny, nz) != PlanSectionState.READY) null
                else sections.sectionAt(nx, ny, nz)
            }
        }
    }

    /** Solid at section-local ([x], [y], [z]), one step outside [data] reads the loaded neighbour. */
    private fun solidNear(data: PlanSection, x: Int, y: Int, z: Int): Boolean {
        val section = when {
            x < 0 -> neighbours[NEIGHBOUR_MINUS_X]
            x >= PLAN_SECTION_EDGE -> neighbours[NEIGHBOUR_PLUS_X]
            y < 0 -> neighbours[NEIGHBOUR_MINUS_Y]
            y >= PLAN_SECTION_EDGE -> neighbours[NEIGHBOUR_PLUS_Y]
            z < 0 -> neighbours[NEIGHBOUR_MINUS_Z]
            z >= PLAN_SECTION_EDGE -> neighbours[NEIGHBOUR_PLUS_Z]
            else -> data
        } ?: return false
        val state = section.stateAt(x and EDGE_MASK, y and EDGE_MASK, z and EDGE_MASK) ?: return false
        return hidesSharedFaces(state)
    }

    /**
     * As ModelBlockRenderer.renderModel, without the faces in [hidden] (a bit for each Direction ordinal). A [flagged]
     * cell turns red on every quad, not only on the tinted ones.
     */
    @Suppress("LongParameterList") // renderModel's parameters, with the hidden faces and the flag.
    private fun renderModel(
        pose: PoseStack.Pose, state: BlockState, model: BakedModel, r: Float, g: Float, b: Float, hidden: Int, flagged: Boolean,
    ) {
        for (direction in Direction.entries) {
            if (hidden and (1 shl direction.ordinal) != 0) continue
            // renderModel seeds 42 before each quad list, so random models pick the same variants.
            modelRandom.setSeed(MODEL_SEED)
            putQuads(pose, model.getQuads(state, direction, modelRandom), r, g, b, flagged)
        }
        modelRandom.setSeed(MODEL_SEED)
        putQuads(pose, model.getQuads(state, null, modelRandom), r, g, b, flagged)
    }

    @Suppress("LongParameterList") // A quad list, its biome tint and the flag.
    private fun putQuads(pose: PoseStack.Pose, quads: List<BakedQuad>, r: Float, g: Float, b: Float, flagged: Boolean) {
        for (i in quads.indices) {
            val quad = quads[i]
            // As vanilla, the biome tint goes only on tinted quads.
            val tinted = quad.isTinted
            val red = if (flagged) 1f else if (tinted) r else 1f
            val green = (if (tinted) g else 1f) * if (flagged) FLAG_DIM else 1f
            val blue = (if (tinted) b else 1f) * if (flagged) FLAG_DIM else 1f
            builder.putBulkData(pose, quad, red, green, blue, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
        }
    }

    private fun draw(mesh: GhostMesh, base: Matrix4f, dx: Double, dy: Double, dz: Double) {
        if (visible.isEmpty) return
        val shader = GameRenderer.getRendertypeTranslucentShader() ?: return
        val mc = Minecraft.getInstance()
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS)
        mc.gameRenderer.lightTexture().turnOnLightLayer()
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(false)
        for (i in 0 until visible.size) {
            val gpu = mesh.gpu.get(visible.getInt(i)) ?: continue
            gpu.lastDrawn = frame
            val buffer = gpu.buffer ?: continue
            RenderSystem.setShaderColor(1f, 1f, 1f, GHOST_ALPHA * visibleFade.getFloat(i))
            modelView.set(base).translate((dx + gpu.boxX).toFloat(), (dy + gpu.boxY).toFloat(), (dz + gpu.boxZ).toFloat())
            buffer.bind()
            buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader)
        }
        VertexBuffer.unbind()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        mc.gameRenderer.lightTexture().turnOffLightLayer()
        drawMarks(mesh, base, dx, dy, dz)
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
    }

    /** The red marks, after the blocks, with blend on and no depth write; both sides, as the player may stand inside one. */
    private fun drawMarks(mesh: GhostMesh, base: Matrix4f, dx: Double, dy: Double, dz: Double) {
        val shader = GameRenderer.getPositionColorShader() ?: return
        RenderSystem.disableCull()
        for (i in 0 until visible.size) {
            val gpu = mesh.gpu.get(visible.getInt(i)) ?: continue
            val marks = gpu.marks ?: continue
            RenderSystem.setShaderColor(1f, 1f, 1f, visibleFade.getFloat(i))
            modelView.set(base).translate((dx + gpu.boxX).toFloat(), (dy + gpu.boxY).toFloat(), (dz + gpu.boxZ).toFloat())
            marks.bind()
            marks.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader)
        }
        VertexBuffer.unbind()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.enableCull()
    }

    /** Frees the least recently drawn sections past the byte budget; the scan runs only when the budget is passed. */
    private fun evict() {
        if (liveBytes <= GPU_BYTE_BUDGET) return
        val count = live.size
        val stamps = LongArray(count) { live[it].lastDrawn }
        val bytes = LongArray(count) { live[it].bytes }
        val chosen = ghostEvictions(stamps, bytes, count, GPU_BYTE_BUDGET, frame)
        if (chosen.isEmpty()) return
        val gone = java.util.BitSet(count)
        for (i in chosen) {
            val gpu = live[i]
            gone.set(i)
            gpu.mesh.gpu.remove(gpu.index)
            closeBuffers(gpu)
            gpu.version = UNBUILT
        }
        var kept = 0
        for (i in 0 until count) if (!gone.get(i)) live[kept++] = live[i]
        live.subList(kept, count).clear()
    }

    private fun release(gpu: GpuSection) {
        if (gpu.buffer == null && gpu.marks == null) return
        closeBuffers(gpu)
        live.remove(gpu)
    }

    private fun closeBuffers(gpu: GpuSection) {
        gpu.buffer?.close()
        gpu.buffer = null
        gpu.marks?.close()
        gpu.marks = null
        liveBytes -= gpu.bytes
        gpu.bytes = 0
    }

    private fun free(gpu: GpuSection) {
        release(gpu)
        gpu.version = UNBUILT
    }

    /** Frees the GPU buffers, for example on leaving the world. */
    fun clear() {
        meshes.values.toList().forEach(::drop)
        meshes.clear()
        check(live.isEmpty()) { "ghost buffers left after clear" }
        liveBytes = 0
    }

    /** The job whose ghost marks this placed plan carries, or null. */
    private fun jobAt(plan: PlanRef, anchor: PlanAnchor): ClientBuildJob? {
        for (job in ClientBuildJobs.jobs()) if (job.ref.key == plan.key && job.anchor == anchor) return job
        return null
    }

    /** The user's rule: a section shows within two chunks of the camera, fading out over the last [FADE_DISTANCE]. */
    private fun ghostFade(distanceSq: Double): Float {
        if (distanceSq >= SHOW_DISTANCE.toDouble() * SHOW_DISTANCE) return 0f
        val d = sqrt(distanceSq)
        return ((SHOW_DISTANCE - d) / FADE_DISTANCE).coerceIn(0.0, 1.0).toFloat()
    }

    /** The plan pose as a matrix, from the same transform the build uses. */
    private fun axesOf(rotation: Rotation, mirror: Mirror): PlanAxes {
        val ex = StructureTemplate.transform(BlockPos(1, 0, 0), mirror, rotation, BlockPos.ZERO)
        val ey = StructureTemplate.transform(BlockPos(0, 1, 0), mirror, rotation, BlockPos.ZERO)
        val ez = StructureTemplate.transform(BlockPos(0, 0, 1), mirror, rotation, BlockPos.ZERO)
        return PlanAxes(intArrayOf(ex.x, ey.x, ez.x, ex.y, ey.y, ez.y, ex.z, ey.z, ez.z))
    }

    private const val UNBUILT = Int.MIN_VALUE
    private const val NO_JOB = -1
    private const val MAX_MESHES = 8
    private const val LOAD_FACTOR = 0.75f
    private const val BOX_INTS = 6
    private const val BUILDER_BYTES = 1 shl 18
    private const val MAX_BUILDS_PER_FRAME = 4
    private const val BUILD_NANOS = 2_000_000L
    private const val MAX_ASKS_PER_FRAME = 16
    private const val SHOW_DISTANCE = 32
    private const val FADE_DISTANCE = 8.0
    private const val HASH_STEP = 31
    private const val GPU_BYTE_BUDGET = 256L shl 20
    private const val GHOST_ALPHA = 0.45f
    private const val FLAG_DIM = 0.25f
    private const val MODEL_SEED = 42L
    private const val MARK_GROW = 0.01f
    private const val MARK_ALPHA = 0.35f
    private const val MARK_BUILDER_BYTES = 1 shl 14
    private const val EDGE_MASK = PLAN_SECTION_EDGE - 1
    private const val NEIGHBOURS = 6

    // loadNeighbours slots: axis * 2 + (0 for the minus side, 1 for the plus side).
    private const val NEIGHBOUR_MINUS_X = 0
    private const val NEIGHBOUR_PLUS_X = 1
    private const val NEIGHBOUR_MINUS_Y = 2
    private const val NEIGHBOUR_PLUS_Y = 3
    private const val NEIGHBOUR_MINUS_Z = 4
    private const val NEIGHBOUR_PLUS_Z = 5
}
