package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Client-side mirror of [org.shipwrights.enderkinesis.body.GamylanCageRegistry].
 * Populated by [GamylanCageNetworkClient]. Holds the full render
 * payload + a lazily-built transient render entity (a captured-NBT
 * reconstruction posed to the moment of capture; never ticked, never
 * added to the world). The renderer reads from here every frame.
 *
 * Per-dimension keying matches the server side — the client only
 * shows cages in the level it's currently in.
 */
object GamylanCageClientRegistry {

    class Entry(
        val bodyId: Long,
        val capturedTag: CompoundTag,
        val animationTag: CompoundTag,
        val captureOrigin: Vector3d,
        val hoverPos: Vector3d,
        /** Crystal shape dims (rx, midH, tipLen). Mirror of
         *  [org.shipwrights.enderkinesis.body.GamylanCageData.shapeDims]. */
        val shapeDims: Vector3d,
        val addedAtGameTime: Long,
        val maxHp: Int,
        @Volatile var hp: Int,
        /** Captured target was a player — UUID drives the client-side
         *  GameProfile lookup so the ghost renders with the player's
         *  skin/cape. */
        val playerProfileUuid: java.util.UUID? = null,
        val playerName: String? = null,
        /** Skin-layer visibility byte (cape / jacket / sleeves / pants
         *  / hat). Applied to the RemotePlayer ghost's `SynchedEntityData`
         *  so the model draws every layer the captured player had on. */
        val playerCustomizationByte: Byte = 0,
        /** 6 slots in EquipmentSlot index order — MAINHAND, OFFHAND,
         *  FEET, LEGS, CHEST, HEAD. Set on the ghost via
         *  `setItemSlot` so armor + held items render. */
        val playerEquipment: List<net.minecraft.world.item.ItemStack> = emptyList(),
        /** Pedestal-conjured item cages: the item shown inside the crystal
         *  (empty for entity captures — the ghost renders instead). */
        val displayedItem: net.minecraft.world.item.ItemStack = net.minecraft.world.item.ItemStack.EMPTY,
        @Volatile var renderEntity: Entity? = null,
        @Volatile var renderEntityFailed: Boolean = false,
    ) {
        /** Packed 0xRRGGBB tint used by the crystal shell rim + noise
         *  pass. Three-tier derivation:
         *
         *   1. Spawn-egg brightest — whichever of the vanilla spawn
         *      egg's base or accent is perceptually brighter.
         *   2. Texture most-saturated — for mobs without a spawn egg,
         *      open the entity's primary texture and pick the highest-
         *      chroma pixel. Skipped for player skins because they
         *      live only on the GPU (HttpTexture).
         *   3. Fallback — Wohlon blue.
         *
         *  Player captures also kick off an async Mojang skin fetch in
         *  [GamylanCageNetworkClient.installPlayerInfo]; when it lands,
         *  the sampled skin colour is written back into
         *  [overrideCrystalColor] and wins over the derivation above.
         *  Volatile so the render thread reads the update without a
         *  lock. */
        @Volatile var overrideCrystalColor: Int = -1

        val crystalColor: Int
            get() {
                val override = overrideCrystalColor
                if (override != -1) return override
                val cached = derivedCrystalColorCache
                if (cached != -1) return cached
                val computed = deriveCrystalColor()
                derivedCrystalColorCache = computed
                return computed
            }

        @Volatile private var derivedCrystalColorCache: Int = -1

        private fun deriveCrystalColor(): Int {
            if (!displayedItem.isEmpty) {
                return itemTextureMostSaturatedColor(displayedItem) ?: FALLBACK_CRYSTAL_COLOR
            }
            spawnEggBrightestColor(capturedTag)?.let { return it }
            val entity = renderEntityFor(this) ?: return FALLBACK_CRYSTAL_COLOR
            return textureMostSaturatedColor(entity) ?: FALLBACK_CRYSTAL_COLOR
        }
    }

    /** Default crystal tint: Wohlon blue `(140, 200, 240)`. */
    const val FALLBACK_CRYSTAL_COLOR: Int = 0x8CC8F0

    /** Spawn-egg brightest colour — see [Entry.crystalColor] step 1.
     *  Returns null when the captured NBT has no `id` (player capture),
     *  the entity type doesn't resolve, or vanilla ships no spawn egg
     *  for that type. */
    private fun spawnEggBrightestColor(capturedTag: CompoundTag): Int? {
        val id = capturedTag.getString("id")
        if (id.isEmpty()) return null
        val entityType = EntityType.byString(id).orElse(null) ?: return null
        val egg = net.minecraft.world.item.SpawnEggItem.byId(entityType) ?: return null
        val base = egg.getColor(0)
        val accent = egg.getColor(1)
        return if (luminance(base) >= luminance(accent)) base else accent
    }

    /** Rec. 601 luma (integer form, scale ×1000). Used for the base-
     *  vs-accent brightness comparison in [spawnEggBrightestColor];
     *  absolute scale doesn't matter — just consistent ordering. */
    private fun luminance(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 299 * r + 587 * g + 114 * b
    }

    /** Minimum chroma (`max - min` across RGB) for a pixel to count as
     *  "coloured enough" to represent the entity. Below this, every
     *  pixel is basically greyscale (wither skeleton, iron golem body
     *  bits with dulled palette) and we fall through to Wohlon rather
     *  than crown a near-black or near-white pixel king. */
    private const val MIN_TEXTURE_CHROMA: Int = 20

    /** Sentinel cached for textures where no pixel exceeded
     *  [MIN_TEXTURE_CHROMA] — lets the cache short-circuit without a
     *  wrapper type. Real textures cannot produce a negative packed
     *  RGB, so collision-with-a-valid-colour is impossible. */
    private const val TEXTURE_COLOR_NONE: Int = -1

    /** Per-texture cache. Keyed by `ResourceLocation` so multiple
     *  captures of the same mob type share one PNG iteration. */
    private val TEXTURE_COLOR_CACHE: ConcurrentHashMap<ResourceLocation, Int> = ConcurrentHashMap()

    /** Resolve the entity's primary texture via its
     *  [EntityRenderer.getTextureLocation] and return the packed RGB
     *  of the pixel with the highest chroma. Returns null when the
     *  renderer / texture / resource can't be resolved or the texture
     *  is essentially greyscale (max chroma below [MIN_TEXTURE_CHROMA]).
     *
     *  Notably NULL for player skins — vanilla loads them via
     *  `HttpTexture` straight into a GPU handle, so
     *  [Minecraft.getResourceManager] doesn't know about that
     *  `ResourceLocation`. We take that as "no colour signal" and let
     *  the caller pick Wohlon. */
    private fun textureMostSaturatedColor(entity: Entity): Int? {
        val dispatcher = Minecraft.getInstance().entityRenderDispatcher
        @Suppress("UNCHECKED_CAST")
        val renderer = (dispatcher.getRenderer(entity) as? EntityRenderer<Entity>) ?: return null
        val texLoc: ResourceLocation = try {
            renderer.getTextureLocation(entity)
        } catch (t: Throwable) { return null }

        return cachedMostSaturated(texLoc)
    }

    /** Item-cage tint. Two stages:
     *
     *   1. **Dynamic layer tints** — spawn eggs, potions, tipped arrows,
     *      dyed leather build their colour at render time via [net.minecraft.client.color.item.ItemColors]
     *      over greyscale textures, so a pixel scan would come back
     *      near-monochrome. The brightest non-white layer tint (mirroring
     *      the mob path's brighter-of-base/accent spawn-egg rule) wins.
     *   2. **All-sprite pixel scan** — collect every atlas sprite the
     *      baked model's quads reference (multi-texture models like the
     *      staffs put their identity colour on a gem layer, NOT on the
     *      `particle` sprite) plus the particle icon, PNG-scan each, and
     *      take the highest-chroma pixel across all of them.
     *
     *  Null (→ Wohlon fallback) only when neither stage finds colour —
     *  e.g. BEWLR items with no quads and no tint. */
    private fun itemTextureMostSaturatedColor(stack: net.minecraft.world.item.ItemStack): Int? {
        val mc = Minecraft.getInstance()
        dynamicTintColor(mc, stack)?.let { return it }

        val model = mc.itemRenderer.getModel(stack, null, null, 0)
        val rand = net.minecraft.util.RandomSource.create(0)
        val sprites = LinkedHashSet<ResourceLocation>()
        for (dir in arrayOf<net.minecraft.core.Direction?>(null) + net.minecraft.core.Direction.values()) {
            for (quad in model.getQuads(null, dir, rand)) {
                sprites.add(quad.sprite.contents().name())
            }
        }
        sprites.add(model.particleIcon.contents().name())

        var best: Int? = null
        var bestChroma = -1
        for (name in sprites) {
            val texLoc = ResourceLocation(name.namespace, "textures/${name.path}.png")
            val c = cachedMostSaturated(texLoc) ?: continue
            val chroma = chromaOf(c)
            if (chroma > bestChroma) {
                bestChroma = chroma
                best = c
            }
        }
        return best
    }

    /** Brightest non-white [net.minecraft.client.color.item.ItemColors]
     *  layer tint for [stack] (layers 0..3), or null when no handler
     *  colours it. `-1` = no handler for that layer; pure white is the
     *  "untinted layer" convention — both skipped. */
    private fun dynamicTintColor(mc: Minecraft, stack: net.minecraft.world.item.ItemStack): Int? {
        val colors = (mc as? org.shipwrights.enderkinesis.mixin.MinecraftItemColorsAccessor)
            ?.`enderkinesis$getItemColors`() ?: return null
        var best: Int? = null
        var bestLuma = -1
        for (layer in 0..3) {
            val c = try { colors.getColor(stack, layer) } catch (_: Throwable) { -1 }
            if (c == -1) continue
            val rgb = c and 0xFFFFFF
            if (rgb == 0xFFFFFF) continue
            val luma = luminance(rgb)
            if (luma > bestLuma) {
                bestLuma = luma
                best = rgb
            }
        }
        return best
    }

    private fun chromaOf(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /** Shared cache wrapper around [sampleMostSaturatedFromResource]. */
    private fun cachedMostSaturated(texLoc: ResourceLocation): Int? {
        val cached = TEXTURE_COLOR_CACHE[texLoc]
        if (cached != null) return if (cached == TEXTURE_COLOR_NONE) null else cached

        val computed = sampleMostSaturatedFromResource(texLoc) ?: TEXTURE_COLOR_NONE
        TEXTURE_COLOR_CACHE[texLoc] = computed
        return if (computed == TEXTURE_COLOR_NONE) null else computed
    }

    private fun sampleMostSaturatedFromResource(texLoc: ResourceLocation): Int? {
        val resourceManager = Minecraft.getInstance().resourceManager
        val resource = resourceManager.getResource(texLoc).orElse(null) ?: return null
        return try {
            resource.open().use { stream -> sampleMostSaturatedFromStream(stream) }
        } catch (t: Throwable) { null }
    }

    /** Most-saturated pixel from any PNG source. Extracted so the
     *  player-skin path (which downloads from Mojang's textures URL and
     *  never touches the ResourceManager) can share the same
     *  [MIN_TEXTURE_CHROMA] threshold + null semantics. */
    fun sampleMostSaturatedFromStream(stream: java.io.InputStream): Int? {
        return try {
            val image = NativeImage.read(stream)
            try {
                var bestChroma = 0
                var bestRgb = 0
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val abgr = image.getPixelRGBA(x, y)
                        val a = (abgr ushr 24) and 0xFF
                        if (a < 128) continue
                        val r = abgr and 0xFF
                        val g = (abgr ushr 8) and 0xFF
                        val b = (abgr ushr 16) and 0xFF
                        val maxC = kotlin.math.max(kotlin.math.max(r, g), b)
                        val minC = kotlin.math.min(kotlin.math.min(r, g), b)
                        val chroma = maxC - minC
                        if (chroma > bestChroma) {
                            bestChroma = chroma
                            bestRgb = (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                if (bestChroma < MIN_TEXTURE_CHROMA) null else bestRgb
            } finally {
                image.close()
            }
        } catch (t: Throwable) { null }
    }

    private val byDimension: ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, Entry>> =
        ConcurrentHashMap()

    fun hasNone(dim: ResourceKey<Level>): Boolean = byDimension[dim].isNullOrEmpty()

    fun forEachIn(dim: ResourceKey<Level>, action: (Entry) -> Unit) {
        byDimension[dim]?.values?.forEach(action)
    }

    fun add(dim: ResourceKey<Level>, entry: Entry) {
        byDimension.computeIfAbsent(dim) { ConcurrentHashMap() }[entry.bodyId] = entry
    }

    fun remove(dim: ResourceKey<Level>, bodyId: Long) {
        byDimension[dim]?.remove(bodyId)
    }

    fun setHp(dim: ResourceKey<Level>, bodyId: Long, hp: Int) {
        byDimension[dim]?.get(bodyId)?.let { it.hp = hp }
    }

    /** Look up the entry for a specific body id in the given dimension.
     *  Used by [GamylanCageNetworkClient] to seed a shatter before the
     *  entry is removed from the registry on CAGE_REMOVED. */
    fun entryFor(dim: ResourceKey<Level>, bodyId: Long): Entry? =
        byDimension[dim]?.get(bodyId)

    /** Snapshot of cages in the player's current level. */
    fun currentLevelEntries(): Collection<Entry> {
        val dim = Minecraft.getInstance().level?.dimension() ?: return emptyList()
        return byDimension[dim]?.values ?: emptyList()
    }

    /** Resolve the body's CURRENT world position. Returns the entry's
     *  captured-time `hoverPos` as a fallback when the level / body
     *  isn't available (early-frame race after add, body gone). On
     *  client the body's `renderTransform` is used for sub-tick
     *  interpolation; falls back to `kinematics.position` if the body
     *  isn't a `ClientVsBody` for some reason. */
    fun livePosition(entry: Entry): Vector3d {
        val level = Minecraft.getInstance().level ?: return Vector3d(entry.hoverPos)
        val world = try { level.shipObjectWorld } catch (_: Throwable) { return Vector3d(entry.hoverPos) }
        val body = world.allBodies.getById(entry.bodyId) ?: return Vector3d(entry.hoverPos)
        return if (body is ClientVsBody) {
            val out = Vector3d()
            body.renderTransform.toWorld.transformPosition(out)
            out
        } else {
            Vector3d(body.kinematics.position)
        }
    }

    /** Resolve the body's CURRENT world rotation as a [Quaternionf]
     *  suitable for `PoseStack.mulPose`. Returns identity when the
     *  body isn't resolvable. */
    fun liveRotation(entry: Entry): Quaternionf {
        val level = Minecraft.getInstance().level ?: return Quaternionf()
        val world = try { level.shipObjectWorld } catch (_: Throwable) { return Quaternionf() }
        val body = world.allBodies.getById(entry.bodyId) ?: return Quaternionf()
        val q = if (body is ClientVsBody) body.renderTransform.rotation else body.kinematics.rotation
        return Quaternionf(q.x().toFloat(), q.y().toFloat(), q.z().toFloat(), q.w().toFloat())
    }

    /** Resolve the body's CURRENT uniform scale (X-axis of the
     *  transform's scaling). Drives the Staff-of-Scales shrink/grow
     *  visual; collision automatically uses the same factor via VS2's
     *  body transform pipeline. Returns 1.0 when the body isn't
     *  resolvable. */
    fun liveScale(entry: Entry): Float {
        val level = Minecraft.getInstance().level ?: return 1f
        val world = try { level.shipObjectWorld } catch (_: Throwable) { return 1f }
        val body = world.allBodies.getById(entry.bodyId) ?: return 1f
        val s = if (body is ClientVsBody) body.renderTransform.scaling else body.kinematics.transform.scaling
        return s.x().toFloat()
    }

    /** Reconstruct + pose the captured entity for rendering. Cached
     *  per-entry after the first call. Returns null while the level
     *  isn't ready or the captured type couldn't be deserialised.
     *  Player captures take a different path: `Player.saveAsPassenger`
     *  refuses, so the entry carries the player's UUID + name and we
     *  build a [RemotePlayer] off the GameProfile here (which gives us
     *  the player's skin / cape automatically via vanilla skin lookup). */
    fun renderEntityFor(entry: Entry): Entity? {
        val cached = entry.renderEntity
        if (cached != null) return cached
        // An item cage has no entity, and `EntityType.create` logs a warning for an empty tag.
        if (!entry.displayedItem.isEmpty || entry.renderEntityFailed) return null
        val level = Minecraft.getInstance().level ?: return null
        val playerUuid = entry.playerProfileUuid
        val playerName = entry.playerName
        val built: Entity? = if (playerUuid != null && playerName != null) {
            val profile = com.mojang.authlib.GameProfile(playerUuid, playerName)
            net.minecraft.client.player.RemotePlayer(level, profile)
        } else {
            EntityType.create(entry.capturedTag, level).orElse(null)
        }
        if (built == null) {
            entry.renderEntityFailed = true
            return null
        }
        if (built is LivingEntity) {
            // Apply the captured facing; motion fields (attackAnim /
            // swingTime / walkAnimation) are left at the fresh entity's
            // zero defaults so the model holds its idle pose. tickCount
            // is preserved so any tickCount-driven idle ambience
            // (enderman eye phase, etc.) stays at the captured-time
            // frame instead of frame 0.
            val a = entry.animationTag
            built.setYRot(a.getFloat("YRot"))
            built.setXRot(a.getFloat("XRot"))
            built.yRotO = a.getFloat("YRot")
            built.xRotO = a.getFloat("XRot")
            built.yBodyRot = a.getFloat("YBodyRot")
            built.yHeadRot = a.getFloat("YHeadRot")
            built.yBodyRotO = a.getFloat("YBodyRotO")
            built.yHeadRotO = a.getFloat("YHeadRotO")
            built.tickCount = a.getInt("TickCount")
        }
        // Player-only: sync skin-layer visibility and equipment slots
        // so the ghost RemotePlayer renders the way the source player
        // looked. Customization byte is the same SynchedEntityData key
        // the vanilla client sync uses; equipment slots feed vanilla's
        // PlayerRenderer HumanoidArmorLayer + arm slot layers.
        if (built is net.minecraft.client.player.RemotePlayer && entry.playerEquipment.isNotEmpty()) {
            built.entityData.set(
                org.shipwrights.enderkinesis.mixin.PlayerCustomisationAccessor
                    .`enderkinesis$getDataCustomisation`(),
                entry.playerCustomizationByte,
            )
            val slots = net.minecraft.world.entity.EquipmentSlot.values()
            for ((i, stack) in entry.playerEquipment.withIndex()) {
                if (i >= slots.size) break
                built.setItemSlot(slots[i], stack)
            }
        }
        entry.renderEntity = built
        return built
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        byDimension.clear()
    }
}
