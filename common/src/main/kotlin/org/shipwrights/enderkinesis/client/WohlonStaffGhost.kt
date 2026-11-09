package org.shipwrights.enderkinesis.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import java.nio.ByteBuffer
import java.util.WeakHashMap
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import org.joml.Matrix3d
import org.joml.Matrix4d
import org.joml.Matrix4f
import org.joml.Quaterniond
import org.joml.Vector3d
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.block.WohlonStaffRites
import org.shipwrights.enderkinesis.entity.WohlonRitePedestal
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.mixin.ClientAdvancementsProgressAccessor

/**
 * The staff a Rite of the Staff makes, as a volume of its own item model: the shaft stands upright over the
 * cradle and turns slowly round it. Gem elements show in the gem colour; the wood fills from the bottom, one
 * band for each slot given. Render thread only, except the voxelization.
 */
object WohlonStaffGhost {

    private val LOG = LogUtils.getLogger()

    /** Grid voxels per model pixel. */
    private const val RES = 2
    private const val PAD_PX = 3.0
    private const val MAX_DIM = 128
    private const val MARGIN_ZERO = 128
    private const val MARGIN_PER_VOXEL = 16f
    private const val LOCAL_SCALE = 16f * RES
    private const val MARGIN_UNIT = 4
    private const val WARP_UNIT = 5
    private const val MAX_QUEUED = 4

    private const val LIFT = 0.15
    const val BASE_HEIGHT = WohlonRitePedestal.CRADLE_HEIGHT + LIFT
    private const val SPIN_RAD_PER_SEC = 0.5
    private const val APPEAR_SEC = 2.5
    // Grid voxels a second that the fill line climbs to a new slot.
    private const val FILL_RATE = 24.0
    private const val TIME_PERIOD_SEC = 4000.0

    private class Grid(
        val dx: Int, val dy: Int, val dz: Int, val margin: ByteBuffer,
        /** The shaft in grid space: it stands on (axisX, *, axisZ). */
        val axisX: Float, val axisZ: Float,
        val woodMinY: Float, val woodMaxY: Float,
    ) {
        var texture = -1
    }

    private class Shape(@Volatile var grid: Grid?)

    /** Per pedestal: when its ghost came, and how far the fill line has climbed. */
    private class Ghost(val startSec: Double) {
        var fillY = Float.NaN
        var lastSec = startSec
    }

    private val shapes = HashMap<Item, Shape>()
    private val ghosts = WeakHashMap<WohlonRitePedestal, Ghost>()
    private val queued = arrayOfNulls<WohlonRitePedestal>(MAX_QUEUED)
    private val queuedPartial = FloatArray(MAX_QUEUED)
    private var count = 0

    private val view = VolumeView()
    private val effect = VolumeEffect("wohlon_staff_ghost")
    private val localToViewD = Matrix4d()
    private val localToView = Matrix4f()
    private val viewToLocal = Matrix4d()
    private val camLocal = Vector3d()

    fun onDisconnect() {
        for (shape in shapes.values) shape.grid?.let(::free)
        shapes.clear()
        ghosts.clear()
    }

    /** Writes the wood's lowest and highest points, in blocks above [BASE_HEIGHT], to [out]; false until the shape is ready. */
    fun woodSpan(result: Item, out: DoubleArray): Boolean {
        val grid = shapes[result]?.grid ?: return false
        out[0] = grid.woodMinY / LOCAL_SCALE.toDouble()
        out[1] = grid.woodMaxY / LOCAL_SCALE.toDouble()
        return true
    }

    /** Called from the pedestal renderer; the volume pass at the end of the frame draws the queue. */
    fun queue(pedestal: WohlonRitePedestal, partialTick: Float) {
        if (count >= MAX_QUEUED || pedestal.staffResult.isEmpty) return
        for (i in 0 until count) if (queued[i] === pedestal) return
        queued[count] = pedestal
        queuedPartial[count] = partialTick
        count++
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (count == 0) return
        val eff = effect.get()
        if (eff != null && !IrisCompat.isShaderPackInUse()) {
            val main = Minecraft.getInstance().mainRenderTarget
            val cam = camera.position
            view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            eff.safeGetUniform("ScreenSize").set(main.width.toFloat(), main.height.toFloat())
            eff.safeGetUniform("LocalScale").set(LOCAL_SCALE)
            val now = System.nanoTime() * 1e-9
            val phase = now % (2.0 * TIME_PERIOD_SEC)
            eff.safeGetUniform("Time").set((if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase).toFloat())
            VolumeTextures.bind3D(WARP_UNIT, WohlonNoiseTexture.id3D())
            var drawn = 0
            for (i in 0 until count) {
                val pedestal = queued[i] ?: continue
                if (draw(eff, pedestal, queuedPartial[i], now, drawn == 0, main)) drawn++
            }
            if (drawn > 0) VolumeProxy.end(main, eff)
            VolumeTextures.bind3D(MARGIN_UNIT, 0)
            VolumeTextures.bind3D(WARP_UNIT, 0)
        }
        queued.fill(null)
        count = 0
    }

    // The render thread reads the client ship's render transform, not physics state.
    @OptIn(GameTickOnly::class)
    @Suppress("LongParameterList") // One ghost's draw state.
    private fun draw(
        eff: net.minecraft.client.renderer.EffectInstance, pedestal: WohlonRitePedestal, partialTick: Float,
        now: Double, first: Boolean, main: com.mojang.blaze3d.pipeline.RenderTarget,
    ): Boolean {
        val grid = shapeFor(pedestal)?.grid ?: return false
        if (grid.texture == -1) grid.texture = upload(grid)
        val ghost = ghosts.getOrPut(pedestal) { Ghost(now) }

        val slots = pedestal.staffSlots.coerceAtLeast(1)
        val share = pedestal.filledSlots.coerceIn(0, slots).toFloat() / slots
        // A full staff fills past its top, so the soft edge leaves no ghost at the tip.
        val target = if (share >= 1f) grid.dy.toFloat() + 4f else grid.woodMinY + (grid.woodMaxY - grid.woodMinY) * share
        if (ghost.fillY.isNaN()) ghost.fillY = grid.woodMinY
        val step = ((now - ghost.lastSec) * FILL_RATE).toFloat()
        ghost.lastSec = now
        ghost.fillY = if (ghost.fillY < target) minOf(target, ghost.fillY + step) else maxOf(target, ghost.fillY - step)

        val closing = pedestal.closingTick
        val t = pedestal.tickCount + partialTick
        val strength = if (closing < 0) 1f else (1f - (t - closing) / WohlonRitePedestal.CLOSE_TICKS).coerceIn(0f, 1f)
        if (strength <= 0f) return false

        // A ship pedestal lives in shipyard coordinates, so the ghost goes through the ship transform.
        val ship = Minecraft.getInstance().level?.getShipManagingPos(pedestal.blockPosition()) as? ClientShip
        if (ship == null) {
            localToViewD.translation(pedestal.x - view.camX, pedestal.y + BASE_HEIGHT - view.camY, pedestal.z - view.camZ)
        } else {
            localToViewD.translation(-view.camX, -view.camY, -view.camZ).mul(ship.renderTransform.shipToWorld)
                .translate(pedestal.x, pedestal.y + BASE_HEIGHT, pedestal.z)
        }
        localToViewD
            .rotateY((now - ghost.startSec) * SPIN_RAD_PER_SEC % (Math.PI * 2.0))
            .scale(1.0 / LOCAL_SCALE)
            .translate(-grid.axisX.toDouble(), 0.0, -grid.axisZ.toDouble())
        localToView.set(localToViewD)
        viewToLocal.set(localToViewD).invert().getTranslation(camLocal)

        val colour = pedestal.staffColour
        eff.safeGetUniform("BoxMin").set(0f, 0f, 0f)
        eff.safeGetUniform("BoxMax").set(grid.dx.toFloat(), grid.dy.toFloat(), grid.dz.toFloat())
        eff.safeGetUniform("GridSize").set(grid.dx.toFloat(), grid.dy.toFloat(), grid.dz.toFloat())
        eff.safeGetUniform("LocalToView").set(localToView)
        eff.safeGetUniform("CamLocal").set(camLocal.x.toFloat(), camLocal.y.toFloat(), camLocal.z.toFloat())
        eff.safeGetUniform("FillY").set(ghost.fillY)
        eff.safeGetUniform("Appear").set(((now - ghost.startSec) / APPEAR_SEC).coerceIn(0.0, 1.0).toFloat())
        eff.safeGetUniform("GemColour").set(((colour shr 16) and 0xFF) / 255f, ((colour shr 8) and 0xFF) / 255f, (colour and 0xFF) / 255f)
        val body = pedestal.staffBodyColour
        eff.safeGetUniform("BodyColour").set(((body shr 16) and 0xFF) / 255f, ((body shr 8) and 0xFF) / 255f, (body and 0xFF) / 255f)
        eff.safeGetUniform("BodyTint").set(if (body == WohlonStaffRites.NO_BODY_COLOUR) 0f else 1f)
        eff.safeGetUniform("Strength").set(strength)
        eff.safeGetUniform("Warble").set(if (isKnown(pedestal)) 0f else 1f)
        VolumeTextures.bind3D(MARGIN_UNIT, grid.texture)
        if (first) VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
        VolumeProxy.draw(eff)
        return true
    }

    /** Starts the voxelization on first sight, while the gem still shows; its grid is null until then. */
    private fun shapeFor(pedestal: WohlonRitePedestal): Shape? {
        val result = pedestal.staffResult.item
        shapes[result]?.let { return it }
        val gem = pedestal.sacrifice.item
        val shape = Shape(null)
        shapes[result] = shape
        val model = readModel(result) ?: return shape
        val gemPath = BuiltInRegistries.ITEM.getKey(gem).path
        Util.backgroundExecutor().execute {
            val grid = voxelize(model, gemPath)
            Minecraft.getInstance().execute { shape.grid = grid }
        }
        return shape
    }

    /**
     * A ghost override stands in for an item drawn by an entity model (as the trident is), which has no elements.
     * It is a full model, or `{"model": id, "gem_elements": [index, ...]}` for a model whose gem shares one texture.
     */
    private fun readModel(item: Item): JsonObject? {
        val id = BuiltInRegistries.ITEM.getKey(item)
        val itemModel = ResourceLocation(id.namespace, "models/item/${id.path}.json")
        val ghost = readJson(EnderkinesisMod.id("models/wohlon_rite_ghost/${id.namespace}/${id.path}.json")) ?: return readJson(itemModel)
        val base = ghost.get("model")?.asString?.let(ResourceLocation::tryParse) ?: return ghost
        val model = readJson(ResourceLocation(base.namespace, "models/${base.path}.json")) ?: return null
        ghost.get("gem_elements")?.let { model.add("gem_elements", it) }
        return model
    }

    private fun readJson(location: ResourceLocation): JsonObject? = try {
        Minecraft.getInstance().resourceManager.getResource(location).orElse(null)
            ?.openAsReader()?.use { JsonParser.parseReader(it).asJsonObject }
    } catch (e: Exception) {
        LOG.warn("Staff ghost could not read {}", location, e)
        null
    }

    // The client gets only the advancements it may see, so an unseen one counts as not known.
    private fun isKnown(pedestal: WohlonRitePedestal): Boolean {
        val ids = pedestal.staffAdvancements
        if (ids.isEmpty()) return true
        val advancements = Minecraft.getInstance().connection?.advancements ?: return false
        val progress = (advancements as ClientAdvancementsProgressAccessor).`enderkinesis$getProgress`()
        return ids.all { id -> advancements.advancements.get(id)?.let { progress[it]?.isDone } == true }
    }

    private class Element(val from: Vector3d, val to: Vector3d, val origin: Vector3d, val toLocal: Matrix3d, val toModel: Matrix3d, val gem: Boolean)

    // Worker thread: only the model JSON.
    private fun voxelize(model: JsonObject, gemPath: String): Grid? {
        val elements = parseElements(model, gemPath)
        val shaft = elements.filter { !it.gem }.maxByOrNull { longest(it) } ?: return null
        val (axisCentre, axisDir) = shaftAxis(shaft)
        val gemCentre = elements.filter { it.gem }.map(::centre).fold(Vector3d()) { a, c -> a.add(c) }
        if (elements.any { it.gem } && Vector3d(gemCentre).div(elements.count { it.gem }.toDouble()).sub(axisCentre).dot(axisDir) < 0) {
            axisDir.negate()
        }
        val upright = Quaterniond().rotationTo(axisDir, Vector3d(0.0, 1.0, 0.0))
        val back = Quaterniond(upright).conjugate()

        val min = Vector3d(Double.MAX_VALUE)
        val max = Vector3d(-Double.MAX_VALUE)
        val corner = Vector3d()
        for (e in elements) for (c in 0 until 8) {
            corner.set(if (c and 1 == 0) e.from.x else e.to.x, if (c and 2 == 0) e.from.y else e.to.y, if (c and 4 == 0) e.from.z else e.to.z)
            corner.sub(e.origin).mul(e.toModel).add(e.origin).sub(axisCentre).rotate(upright)
            min.min(corner)
            max.max(corner)
        }
        min.sub(PAD_PX, PAD_PX, PAD_PX)
        max.add(PAD_PX, PAD_PX, PAD_PX)
        val dx = Math.ceil((max.x - min.x) * RES).toInt().coerceIn(1, MAX_DIM)
        val dy = Math.ceil((max.y - min.y) * RES).toInt().coerceIn(1, MAX_DIM)
        val dz = Math.ceil((max.z - min.z) * RES).toInt().coerceIn(1, MAX_DIM)

        val margin = BufferUtils.createByteBuffer(dx * dy * dz * 3)
        var woodMinY = Float.MAX_VALUE
        var woodMaxY = -Float.MAX_VALUE
        val p = Vector3d()
        val q = Vector3d()
        for (z in 0 until dz) for (y in 0 until dy) for (x in 0 until dx) {
            p.set(min.x + (x + 0.5) / RES, min.y + (y + 0.5) / RES, min.z + (z + 0.5) / RES).rotate(back).add(axisCentre)
            var wood = -Float.MAX_VALUE
            var gem = -Float.MAX_VALUE
            for (e in elements) {
                q.set(p).sub(e.origin).mul(e.toLocal).add(e.origin)
                val m = -boxDistance(q, e.from, e.to).toFloat() * RES
                if (e.gem) gem = maxOf(gem, m) else wood = maxOf(wood, m)
            }
            if (wood > 0f) {
                woodMinY = minOf(woodMinY, y.toFloat())
                woodMaxY = maxOf(woodMaxY, y + 1f)
            }
            val i = ((z * dy + y) * dx + x) * 3
            margin.put(i, encode(wood))
            margin.put(i + 1, encode(gem))
            margin.put(i + 2, 0)
        }
        if (woodMinY > woodMaxY) return null
        return Grid(dx, dy, dz, margin, (-min.x * RES).toFloat(), (-min.z * RES).toFloat(), woodMinY, woodMaxY)
    }

    private fun encode(m: Float): Byte = (MARGIN_ZERO + m * MARGIN_PER_VOXEL).toInt().coerceIn(0, 255).toByte()

    private fun parseElements(model: JsonObject, gemPath: String): List<Element> {
        val textures = model.getAsJsonObject("textures") ?: JsonObject()
        val gemKeys = textures.entrySet().filter { it.value.isJsonPrimitive && it.value.asString.contains(gemPath) }.map { "#" + it.key }.toSet()
        val gemIndices = model.getAsJsonArray("gem_elements")?.map { it.asInt }?.toSet() ?: emptySet()
        val list = ArrayList<Element>()
        for ((index, el) in (model.getAsJsonArray("elements") ?: return list).withIndex()) {
            val o = el.asJsonObject
            val from = vec(o.getAsJsonArray("from"))
            val to = vec(o.getAsJsonArray("to"))
            val toModel = Matrix3d()
            var origin = Vector3d(8.0, 8.0, 8.0)
            o.getAsJsonObject("rotation")?.let { r ->
                origin = vec(r.getAsJsonArray("origin"))
                val rad = Math.toRadians(r.get("angle").asDouble)
                when (r.get("axis").asString) {
                    "x" -> toModel.rotationX(rad)
                    "y" -> toModel.rotationY(rad)
                    else -> toModel.rotationZ(rad)
                }
            }
            val faces = o.getAsJsonObject("faces")
            val gem = index in gemIndices || faces != null && faces.entrySet().any { it.value.asJsonObject.get("texture")?.asString in gemKeys }
            list.add(Element(from, to, origin, Matrix3d(toModel).transpose(), toModel, gem))
        }
        return list
    }

    private fun vec(a: com.google.gson.JsonArray) = Vector3d(a[0].asDouble, a[1].asDouble, a[2].asDouble)

    private fun longest(e: Element): Double = maxOf(e.to.x - e.from.x, e.to.y - e.from.y, e.to.z - e.from.z)

    private fun centre(e: Element): Vector3d =
        Vector3d(e.from).add(e.to).mul(0.5).sub(e.origin).mul(e.toModel).add(e.origin)

    /** The shaft's long axis and centre, in model space. */
    private fun shaftAxis(e: Element): Pair<Vector3d, Vector3d> {
        val size = Vector3d(e.to).sub(e.from)
        val local = when {
            size.x >= size.y && size.x >= size.z -> Vector3d(1.0, 0.0, 0.0)
            size.y >= size.z -> Vector3d(0.0, 1.0, 0.0)
            else -> Vector3d(0.0, 0.0, 1.0)
        }
        return centre(e) to local.mul(e.toModel).normalize()
    }

    // Signed distance from q to the box [from, to], in model pixels.
    private fun boxDistance(q: Vector3d, from: Vector3d, to: Vector3d): Double {
        val cx = (from.x + to.x) * 0.5; val cy = (from.y + to.y) * 0.5; val cz = (from.z + to.z) * 0.5
        val dx = Math.abs(q.x - cx) - (to.x - from.x) * 0.5
        val dy = Math.abs(q.y - cy) - (to.y - from.y) * 0.5
        val dz = Math.abs(q.z - cz) - (to.z - from.z) * 0.5
        val outside = Math.sqrt(maxOf(dx, 0.0).let { it * it } + maxOf(dy, 0.0).let { it * it } + maxOf(dz, 0.0).let { it * it })
        return outside + minOf(maxOf(dx, dy, dz), 0.0)
    }

    private fun upload(grid: Grid): Int {
        val tex = GlStateManager._genTexture()
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
        grid.margin.rewind()
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB8, grid.dx, grid.dy, grid.dz, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, grid.margin)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        return tex
    }

    private fun free(grid: Grid) {
        if (grid.texture != -1) GlStateManager._deleteTexture(grid.texture)
        grid.texture = -1
    }
}
