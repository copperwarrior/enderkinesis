package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.ScryingClientNetwork
import org.shipwrights.enderkinesis.scrying.ScryingChunkCacheBridge
import org.shipwrights.enderkinesis.util.Sga
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Client-side state for the scrying-orb remote view.
 *
 *  - **Pose override at camera setup, not a camera-entity swap.** The camera-setup
 *    mixin reads [targetWorldPos] each frame and overwrites the rendered position
 *    after vanilla finishes its own setup. We deliberately do NOT use
 *    `Minecraft.setCameraEntity`: that path also flips the server-side
 *    `ServerPlayer.camera` field, and vanilla's move-packet handler then routes the
 *    client's WASD inputs to whichever entity that field points at — leaving the
 *    player's saved position dragged out to the distant location once the session
 *    ends. Pose-only override keeps the player's body and persisted position intact.
 *
 *  - **Parallel camera chunk storage.** [ScryingChunkCacheBridge] (implemented on
 *    `ClientChunkCache` via mixin) holds a second chunk-storage map keyed at the
 *    target. Vanilla's main storage stays anchored at the player, so the source area
 *    keeps rendering through the whole session; chunks streamed by the server for
 *    the camera area land in the parallel storage and the renderer picks them up
 *    through the same `level.getChunk(x, z)` path it always uses. [beginScrying] arms
 *    the storage at the target's chunk; [updateScrying] re-centers it when a ship-
 *    mounted target moves between chunks; [endScrying] disarms and drops it.
 *
 *  - Mouse aim still rotates the player; the camera at the target reads the player's
 *    rotation through vanilla's setup, so "looking around" at the source is looking
 *    around through the target orb.
 *
 *  - Sneak ends the view. Walking more than [MAX_PROXIMITY_BLOCKS] away from the SOURCE
 *    orb (the one the player right-clicked) also ends it — the orb-of-scrying interaction
 *    is intended as a short-range glance, not a free roving spy camera.
 *
 *  - On dimension change the view ends — the shipyard `BlockPos` is dimension-local.
 */
object ScryingClient {

    /** Maximum distance from the SOURCE orb's world position the player can be while a view
     *  is active. Mirrors the server-side proximity gate so the camera can't sit on a
     *  scrying orb the player walked away from. 1.5 blocks ≈ inside the orb's own AABB +
     *  a small slack so the trigger doesn't fight standard 1-block interaction-distance. */
    private const val MAX_PROXIMITY_BLOCKS: Double = 1.5
    private const val MAX_PROXIMITY_SQR: Double = MAX_PROXIMITY_BLOCKS * MAX_PROXIMITY_BLOCKS

    @Volatile private var sourcePos: BlockPos? = null
    @Volatile private var targetPos: BlockPos? = null
    /** Tracked so we end the view on dimension change — the BlockPos is dimension-local. */
    private var startedInLevel: ClientLevel? = null

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ScryingClientNetwork.BEGIN_SCRYING) { buf, ctx ->
            val src = buf.readBlockPos()
            val tgt = buf.readBlockPos()
            val vd = buf.readVarInt()
            ctx.queue { beginScrying(src, tgt, vd) }
        }
        ClientTickEvent.CLIENT_POST.register { _ -> updateScrying() }
        // Per-frame uniform push for the refraction PostChain is now driven from
        // GuiScryingMixin's HEAD inject — see updateRefractionUniforms KDoc for why.
    }

    /** Active-view query. Volatile read so the GUI overlay and HUD-suppression mixin see
     *  the latest state regardless of which thread set it. */
    @JvmStatic
    fun isActive(): Boolean = targetPos != null

    /** Current world-space position the camera should render from. Recomputes each frame
     *  from the stored shipyard `BlockPos` so when the target orb sits on a VS2 ship the
     *  camera follows the ship's render transform smoothly. Null when no view is active
     *  or the client level isn't ready. */
    @JvmStatic
    fun targetWorldPos(): Vec3? {
        val pos = targetPos ?: return null
        val level = Minecraft.getInstance().level ?: return null
        return shipyardToWorld(level, pos)
    }

    /** Pitch (degrees, MC convention: positive = looking down) from the local player's
     *  eye to the SOURCE orb's current world position. Used by
     *  [org.shipwrights.enderkinesis.mixin.LivingEntityRendererScryingMixin] to override
     *  the head-pitch arg into the model's setupAnim, so the player's head model tilts
     *  toward the orb without touching `xRot` — `xRot` drives the camera direction
     *  through vanilla `Camera.setup`, so overriding it on the entity would yank the
     *  remote camera off the user's mouse aim. Null when no session is active or the
     *  source position can't be resolved. */
    @JvmStatic
    fun scryingHeadPitch(): Float? {
        if (!isActive()) return null
        val src = sourcePos ?: return null
        val level = Minecraft.getInstance().level ?: return null
        val world = shipyardToWorld(level, src) ?: return null
        val player = Minecraft.getInstance().player ?: return null
        val eye = player.eyePosition
        val dx = world.x - eye.x
        val dy = world.y - eye.y
        val dz = world.z - eye.z
        val horiz = Math.sqrt(dx * dx + dz * dz)
        if (horiz < 1.0e-6 && Math.abs(dy) < 1.0e-6) return null
        return (Math.toDegrees(-Math.atan2(dy, horiz))).toFloat()
    }

    private fun beginScrying(source: BlockPos, target: BlockPos, viewDistance: Int) {
        // Single-session invariant for this client. A second BEGIN arriving while we're
        // still mid-session (fade-in / active / fade-out before actuallyEndScrying)
        // needs to tear down the prior state cleanly, otherwise camera storage /
        // refraction / noCulling / fade-state stack. Mirrors the server-side
        // [ScryingSessionManager.mount] preemption keyed by player UUID.
        if (isActive()) {
            LOG.info("Preempting existing client scry session for new one")
            actuallyEndScrying()
        }
        val mc = Minecraft.getInstance()
        startedInLevel = mc.level
        sourcePos = source.immutable()
        targetPos = target.immutable()
        cameraRadius = viewDistance
        sessionStartSeconds = Util.getMillis() / 1000.0
        lastLockedYaw = null
        endRequested = false
        LOG.info("Scrying session begin — source={} target={} viewDistance={}", source, target, viewDistance)
        // Force the player to render even when frustum / distance culling would normally
        // drop them — from the remote-camera vantage the player is behind the camera
        // until they turn around, and often past the entity-distance cutoff too. Pairs
        // with EntityScryingRenderMixin's distance-cull bypass on the local player.
        mc.player?.noCulling = true
        armCameraStorage(target)
        activateRefraction()
        // HOLD at full black, then fade once chunks have arrived. The fade-out lerp is
        // armed in [updateScrying] once either [CHUNK_READY_THRESHOLD] of the camera
        // square has been routed to camera storage, or [MAX_LOAD_WAIT_MS] has elapsed.
        // Without the hold, fast-server cases worked but a long-distance scry against
        // ungenerated chunks revealed a void edge as the fade ran on schedule and the
        // chunks finished arriving afterward.
        fadeStartAlpha = 1f
        fadeTargetAlpha = 1f
        fadeStartMillis = Util.getMillis()
        awaitingChunkLoad = true
        chunkWaitStartMs = Util.getMillis()
    }

    private fun updateScrying() {
        if (targetPos == null) return
        // Fade-completion handoff runs BEFORE the various end-triggers below. If we
        // checked it at the bottom of the method, the proximity / level-change early-
        // returns would skip it on every tick after the end was requested — fade stays
        // pegged at alpha=1 indefinitely until the trigger condition clears (e.g. the
        // player walks back in range). Putting it first means whichever path armed
        // the fade-out, the handoff happens as soon as the lerp reaches full opacity.
        if (endRequested && currentFadeAlpha() >= 0.999f) {
            actuallyEndScrying()
            fadeStartAlpha = 1f
            fadeTargetAlpha = 0f
            fadeStartMillis = Util.getMillis()
            return
        }
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level == null || player == null || level !== startedInLevel) {
            requestEndScrying()
            return
        }
        // Proximity gate — measure from the player's eye position to the SOURCE orb's
        // current world position. For ship-mounted orbs the world position moves with the
        // ship; the gate compares against the rendered location, so a player carried by
        // the ship stays within range automatically.
        val src = sourcePos
        if (src != null) {
            val sourceWorld = shipyardToWorld(level, src)
            if (sourceWorld == null || player.eyePosition.distanceToSqr(sourceWorld) > MAX_PROXIMITY_SQR) {
                requestEndScrying()
                return
            }
            // Lock the player model's body and head yaw to the source orb so the
            // scryer visibly stares at the orb when the camera at the target catches
            // them in frustum. Vanilla's per-tick aiStep resets both rotations, and
            // CLIENT_POST runs AFTER aiStep — our writes survive until the next render
            // and the next tick. Pitch (xRot) is left alone: it drives the camera
            // direction through the pose-override mixin, so overriding it here would
            // force the camera to look at whatever's at the orb's height.
            lockPlayerLookAtOrb(player, sourceWorld)
        }
        if (mc.options.keyShift.isDown) {
            requestEndScrying()
            return
        }
        // (Fade-completion handoff runs at the top of this method, ahead of the
        // proximity / level-change early-returns, so this block was previously
        // duplicating that check.)
        // Begin-fade adaptive hold: keep the overlay opaque until the camera area has
        // streamed in (or the timeout fires), then release into the fade-out lerp.
        if (awaitingChunkLoad) {
            val waited = Util.getMillis() - chunkWaitStartMs
            val ratio = cameraChunksLoadedRatio()
            val readyAfterMin = ratio >= CHUNK_READY_THRESHOLD && waited >= MIN_LOAD_WAIT_MS
            val timedOut = waited >= MAX_LOAD_WAIT_MS
            if (readyAfterMin || timedOut) {
                awaitingChunkLoad = false
                fadeStartAlpha = 1f
                fadeTargetAlpha = 0f
                fadeStartMillis = Util.getMillis()
            }
        }
        // Keep the camera storage centered on the target's CURRENT chunk so a ship-
        // mounted target staying within the radius doesn't drift out of range mid-
        // session. Cheap — only re-arms when the target actually moves between chunks.
        val tgt = targetPos ?: return
        val tw = shipyardToWorld(level, tgt) ?: return
        val cameraChunkX = SectionPos.blockToSectionCoord(BlockPos.containing(tw.x, tw.y, tw.z).x)
        val cameraChunkZ = SectionPos.blockToSectionCoord(BlockPos.containing(tw.x, tw.y, tw.z).z)
        if (cameraChunkX != lastCameraChunkX || cameraChunkZ != lastCameraChunkZ) {
            (mc.level?.chunkSource as? ScryingChunkCacheBridge)?.enderkinesis_setCameraView(
                cameraChunkX, cameraChunkZ, cameraRadius,
            )
            lastCameraChunkX = cameraChunkX
            lastCameraChunkZ = cameraChunkZ
        }
    }

    /** Begin fading the session out. The actual cleanup runs once the overlay reaches
     *  full opacity in [updateScrying] — that way the camera-back-to-player transition
     *  happens behind an opaque overlay instead of a visible frame of "snap back to
     *  source". Idempotent: subsequent calls during the fade are no-ops. */
    private fun requestEndScrying() {
        if (!isActive() || endRequested) return
        endRequested = true
        fadeStartAlpha = currentFadeAlpha()
        fadeTargetAlpha = 1f
        fadeStartMillis = Util.getMillis()
    }

    private fun actuallyEndScrying() {
        sourcePos = null
        targetPos = null
        startedInLevel = null
        lastCameraChunkX = Int.MIN_VALUE
        lastCameraChunkZ = Int.MIN_VALUE
        endRequested = false
        awaitingChunkLoad = false
        val mc = Minecraft.getInstance()
        // Restore the default cull behaviour for the player.
        mc.player?.noCulling = false
        (mc.level?.chunkSource as? ScryingChunkCacheBridge)?.enderkinesis_clearCameraStorage()
        deactivateRefraction()
        ScryingClientNetwork.sendEndScrying()
    }

    /** Initial arm of the camera-chunk storage when a session begins. Sized to the
     *  per-session [cameraRadius] received from the server so every chunk the server
     *  streams passes the range gate. */
    private fun armCameraStorage(target: BlockPos) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val tw = shipyardToWorld(level, target) ?: return
        val cameraChunkX = SectionPos.blockToSectionCoord(BlockPos.containing(tw.x, tw.y, tw.z).x)
        val cameraChunkZ = SectionPos.blockToSectionCoord(BlockPos.containing(tw.x, tw.y, tw.z).z)
        val bridge = level.chunkSource as? ScryingChunkCacheBridge
        if (bridge == null) {
            LOG.warn(
                "ClientChunkCache doesn't implement ScryingChunkCacheBridge — chunk-storage mixin did not weave, camera will render void",
            )
            return
        }
        bridge.enderkinesis_setCameraView(cameraChunkX, cameraChunkZ, cameraRadius)
        LOG.info("Camera storage armed at chunk ({}, {}) radius {}", cameraChunkX, cameraChunkZ, cameraRadius)
        lastCameraChunkX = cameraChunkX
        lastCameraChunkZ = cameraChunkZ
    }

    /** Override the local player's body and head yaw so the model visibly faces the
     *  given world position. Pitch is intentionally left to vanilla — overriding it
     *  would alter the camera direction (the pose-override mixin reads the player's
     *  view rotation through vanilla's `Camera.setup`).
     *
     *  Snapshots prev to the last-locked yaw, NOT to the current `yBodyRot` field.
     *  Vanilla's per-tick `aiStep` calls `LivingEntity.tickHeadTurn` which yanks
     *  `yBodyRot` toward the mouse-driven `yRot` mid-tick; reading from the field
     *  would capture that mid-tick "trying to follow the mouse" angle as prev, and
     *  the render would lerp from there back to the locked yaw — a visible sway-back
     *  every tick that reads as the lock fighting mouse input. With static orb the
     *  prev/current yaws match → no animation. With ship motion they differ by one
     *  tick's worth of drift → smooth lerp. */
    private fun lockPlayerLookAtOrb(player: net.minecraft.client.player.LocalPlayer, target: Vec3) {
        val eye = player.eyePosition
        val dx = target.x - eye.x
        val dz = target.z - eye.z
        if (dx * dx + dz * dz < 1.0e-6) return
        val yaw = (Math.toDegrees(Math.atan2(-dx, dz))).toFloat()
        val prevYaw = lastLockedYaw ?: yaw
        player.yBodyRot = yaw
        player.yBodyRotO = prevYaw
        player.yHeadRot = yaw
        player.yHeadRotO = prevYaw
        lastLockedYaw = yaw
    }

    /** Last yaw the look-at-orb lock wrote, in degrees. Used as the `O`-suffix prev
     *  value next tick so the partialTick lerp stays inside the locked-yaw timeline
     *  instead of starting from vanilla's mid-tick rotation. Reset on session
     *  begin / end so a fresh session starts without a stale carry-over. */
    private var lastLockedYaw: Float? = null

    // --- Fade overlay ------------------------------------------------------------------
    //
    // Fullscreen black overlay lerped between alpha targets to cover the moments when
    // the camera is jumping to / from the target and chunks are streaming in. Three
    // visible phases per session:
    //   - Begin: alpha 1 → 0 over [FADE_DURATION_MS], revealing the camera view as the
    //     ticket square starts arriving on the client.
    //   - End-request: alpha 0 → 1 over the same duration; updateScrying watches for the
    //     overlay to hit ~1.0 then runs actuallyEndScrying behind the opaque frame.
    //   - Post-end: alpha 1 → 0 fading from black to the now-restored source view.
    private const val FADE_DURATION_MS: Long = 700L

    @Volatile private var fadeStartAlpha: Float = 0f
    @Volatile private var fadeTargetAlpha: Float = 0f
    @Volatile private var fadeStartMillis: Long = 0L

    /** Set once [requestEndScrying] runs. Cleared inside [actuallyEndScrying]. While
     *  true, [updateScrying] skips ship-tracking re-arms and just waits for the fade
     *  to reach full opacity. */
    @Volatile private var endRequested: Boolean = false

    /** Threshold of camera-area chunks that need to be present in the parallel storage
     *  before the begin-fade releases. 0.85 lets a small ring of edge chunks finish
     *  while the fade-out lerp covers them — strict 1.0 would stall on chunks that take
     *  unusually long (one slow generator can block the whole session). */
    private const val CHUNK_READY_THRESHOLD: Float = 0.85f

    /** Maximum time the begin-fade holds opaque waiting for chunks. After this, the
     *  fade-out runs regardless — a hung server / bridge failure won't strand the user
     *  staring at a black screen. */
    private const val MAX_LOAD_WAIT_MS: Long = 2000L

    /** Minimum time the begin-fade holds opaque before transitioning to the fade-out
     *  lerp. Even when the chunk-ready threshold is met instantly (nearby targets where
     *  the whole camera area is already in main storage), the overlay holds for at
     *  least this long so the transition reads as a deliberate effect instead of an
     *  imperceptible flicker. */
    private const val MIN_LOAD_WAIT_MS: Long = 500L

    /** True between [beginScrying] and the first frame where chunks-ready or timeout
     *  fires, gating the fade hold. */
    @Volatile private var awaitingChunkLoad: Boolean = false

    @Volatile private var chunkWaitStartMs: Long = 0L

    /** Fraction of the camera square (radius² chunks) effectively ready to render.
     *
     *  Two sources:
     *  - Camera storage (`enderkinesis_cameraChunkCount`) for chunks the route mixin
     *    diverted (camera area chunks outside the player's main-storage range).
     *  - **Main storage coverage** for chunks the route mixin let vanilla put in main
     *    (nearby scry — camera square overlaps the player's view). The mixin's main-
     *    range check means cameraChunks is empty for those, but the chunks ARE loaded
     *    in main and the renderer can query them via the getChunk fallback. Without
     *    this branch the begin-fade ratio stayed at 0 forever for nearby targets,
     *    forcing the [MAX_LOAD_WAIT_MS] timeout (5 s of opaque black) every time.
     *
     *  If the camera square is FULLY covered by main range, return 1.0 immediately —
     *  vanilla's player tracker has already streamed every one of those chunks, so the
     *  begin-fade can release on the first updateScrying tick. */
    private fun cameraChunksLoadedRatio(): Float {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player != null && lastCameraChunkX != Int.MIN_VALUE) {
            val playerChunk = player.chunkPosition()
            val mainRadius = Math.max(2, mc.options.effectiveRenderDistance) + 3
            val furthestX = Math.max(
                Math.abs(lastCameraChunkX + cameraRadius - playerChunk.x),
                Math.abs(lastCameraChunkX - cameraRadius - playerChunk.x),
            )
            val furthestZ = Math.max(
                Math.abs(lastCameraChunkZ + cameraRadius - playerChunk.z),
                Math.abs(lastCameraChunkZ - cameraRadius - playerChunk.z),
            )
            if (furthestX <= mainRadius && furthestZ <= mainRadius) {
                return 1.0f
            }
        }
        val bridge = mc.level?.chunkSource as? ScryingChunkCacheBridge ?: return 0f
        val side = 2 * cameraRadius + 1
        val expected = side * side
        if (expected <= 0) return 0f
        return bridge.enderkinesis_cameraChunkCount().toFloat() / expected
    }

    /** Current overlay alpha — linear interpolation from [fadeStartAlpha] toward
     *  [fadeTargetAlpha], clamped to [0, 1]. Wall-clock-driven (Util.getMillis) so it
     *  advances on every render frame and is independent of TPS or partialTick. */
    private fun currentFadeAlpha(): Float {
        if (fadeTargetAlpha == fadeStartAlpha) return fadeTargetAlpha
        val elapsed = (Util.getMillis() - fadeStartMillis).toFloat()
        val t = (elapsed / FADE_DURATION_MS).coerceIn(0f, 1f)
        return fadeStartAlpha + (fadeTargetAlpha - fadeStartAlpha) * t
    }

    /** Draw the fullscreen black overlay. Called unconditionally from
     *  [org.shipwrights.enderkinesis.mixin.GuiScryingMixin]'s RETURN inject so the fill
     *  lands at the tail of `Gui.render`. No-op when alpha is effectively zero.
     *
     *  Uses [RenderType.guiOverlay] rather than the default `gui()` render type. `gui()`
     *  has `LEQUAL_DEPTH_TEST`: the fill defaults to z=0 which translates to depth ~0.5
     *  in the GUI ortho, and any earlier GUI element that drew at higher z (the SGA
     *  glyphs at z=900 use `RenderType.text` which writes depth ~0.455) blocks the
     *  fade with `0.5 ≤ 0.455 == false`. The fade visibly failed only during begin —
     *  the only phase where glyphs and fade are both drawing — even though the lerp
     *  math was identical to the post-end fade-back that worked. `guiOverlay` has
     *  `NO_DEPTH_TEST + COLOR_WRITE`, so the quad always passes regardless of what
     *  depth values prior GUI draws left in the buffer. */
    @JvmStatic
    fun renderFadeOverlay(graphics: GuiGraphics) {
        val alpha = currentFadeAlpha()
        if (alpha <= 0.005f) return
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        val color = a shl 24
        graphics.fill(RenderType.guiOverlay(), 0, 0, graphics.guiWidth(), graphics.guiHeight(), 0, color)
        graphics.flush()
    }

    /** Camera storage radius for the active session, received from the server via the
     *  BEGIN_SCRYING packet. Matches the server's per-session view distance exactly so
     *  every chunk the server streams passes the in-range check. */
    private var cameraRadius: Int = 0

    private var lastCameraChunkX: Int = Int.MIN_VALUE
    private var lastCameraChunkZ: Int = Int.MIN_VALUE

    /** Whether we have a post-processing chain loaded. Tracked so [deactivateRefraction]
     *  only shuts down OUR effect — if our `loadEffect` call failed (e.g. shaderpack
     *  active, resource pack overrode the shader), or if another mod has loaded a
     *  different post-chain in between, we leave it alone. */
    @Volatile private var refractionActive: Boolean = false

    /** Load the scrying-refraction PostChain. Runs in the world→GUI gap, so the GUI
     *  overlay (vignette + glyphs) composites cleanly on top of the refracted world.
     *  Best-effort: PostChain construction can throw on IO / JSON / GL errors and we
     *  swallow them rather than letting the scrying experience break — the view still
     *  works, just without the rippling glass effect. */
    private fun activateRefraction() {
        val mc = Minecraft.getInstance()
        try {
            (mc.gameRenderer as org.shipwrights.enderkinesis.mixin.GameRendererPostEffectInvoker)
                .`enderkinesis$loadEffect`(EnderkinesisMod.id("shaders/post/scrying_refract.json"))
            refractionActive = true
        } catch (t: Throwable) {
            refractionActive = false
            LOG.warn("Failed to load scrying refraction post-effect", t)
        }
    }

    /** Shut down OUR refraction effect. The "did we actually start it" guard means
     *  ending scrying never accidentally shuts down a post-effect another mod loaded
     *  between our start and end (vanilla only supports one post-chain at a time, so
     *  someone else's would have replaced ours mid-view — we'd rather leave them alone
     *  than disrupt their effect). */
    private fun deactivateRefraction() {
        if (!refractionActive) return
        refractionActive = false
        Minecraft.getInstance().gameRenderer.shutdownEffect()
    }

    /** Push `SessionTime` (and any future per-frame uniforms) onto every pass of our
     *  PostChain so the refract shader's noise actually animates. Vanilla's
     *  `PostPass.process` only auto-sets `Time` (partialTicks) and `ScreenSize`, so a
     *  shader that wants continuous time has to be driven from outside. We walk
     *  GameRenderer → PostChain → PostPass → EffectInstance via the accessor mixins.
     *
     *  Also acts as the **shader keep-alive**: if [refractionActive] is true but
     *  `getPostEffect()` has gone null (some external path tore down our PostChain —
     *  e.g. F4-bound keybinds from other mods that bypass [GameRendererScryingMixin]'s
     *  `checkEntityPostEffect` cancel), we reload our effect on the next frame. Beats
     *  trying to find and intercept every possible teardown path.
     *
     *  Called from [GuiScryingMixin]'s HEAD inject (every `Gui.render` call, regardless
     *  of `hideGui` state) rather than `ClientGuiEvent.RENDER_HUD` — the HUD event is
     *  gated on the same path our hideGui override forces, so it would freeze the
     *  uniform when the user has F1 toggled or our scrying HUD-hide is engaged. */
    @JvmStatic
    fun updateRefractionUniforms() {
        if (!refractionActive) return
        val mc = Minecraft.getInstance()
        val gr = mc.gameRenderer as org.shipwrights.enderkinesis.mixin.GameRendererPostEffectInvoker
        val chain = gr.`enderkinesis$getPostEffect`()
        if (chain == null) {
            // Something killed our post-effect. Reload from scratch.
            try {
                gr.`enderkinesis$loadEffect`(EnderkinesisMod.id("shaders/post/scrying_refract.json"))
            } catch (t: Throwable) {
                LOG.warn("Failed to reload scrying refraction post-effect after external teardown", t)
            }
            return
        }
        val passes = (chain as org.shipwrights.enderkinesis.mixin.PostChainAccessor)
            .`enderkinesis$getPasses`()
        val tSec = (Util.getMillis() / 1000.0 - sessionStartSeconds).toFloat()
        for (pass in passes) {
            val effect = (pass as org.shipwrights.enderkinesis.mixin.PostPassAccessor)
                .`enderkinesis$getEffect`()
            effect.safeGetUniform("SessionTime").set(tSec)
        }
    }

    private val LOG: org.slf4j.Logger =
        org.slf4j.LoggerFactory.getLogger("EnderkinesisScryingClient")

    /** Background pass — vignette shader. Called from
     *  [org.shipwrights.enderkinesis.mixin.GuiScryingMixin] at the HEAD of `Gui.render`
     *  so it sits under chat / F3 / debug / any other HUD that's still allowed to draw. */
    @JvmStatic
    fun renderBackground(graphics: GuiGraphics, partialTick: Float) {
        if (!isActive()) return
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        renderVignette(graphics, w, h)
    }

    /** Foreground pass — drifting SGA-glyph dressing. Called from the same mixin at the
     *  RETURN of `Gui.render`, so the glyphs sit on TOP of whatever vanilla's body drew.
     *  Splitting the overlay this way is what makes the SGA visible: at HEAD their
     *  drawString vertices were queued before the rest of Gui.render ran, and vanilla's
     *  multiplicative vignette + chat composite layers landed on top. */
    @JvmStatic
    fun renderForeground(graphics: GuiGraphics, partialTick: Float) {
        if (!isActive()) return
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        renderSgaGlyphs(graphics, w, h)
    }

    /** Wall-clock instant the current view began, in seconds. Drives the `SessionTime`
     *  uniform on the vignette shader — feeding session-relative time keeps the noise
     *  drift values bounded even on long-running worlds, mirroring the trick the Ygann
     *  overlay uses to dodge precision loss in the fragment hash. */
    @Volatile private var sessionStartSeconds: Double = 0.0

    /** Radial vignette via the custom shader. ONE full-screen quad in POSITION-only
     *  format; the fragment shader computes the radial distance from centre, samples
     *  4-octave fBm noise per pixel, and uses the noise to perturb the gradient threshold
     *  so the vignette boundary undulates rather than tracing a perfect circle. See
     *  `shaders/core/rendertype_scrying_vignette.fsh` for the maths. */
    private fun renderVignette(graphics: GuiGraphics, w: Int, h: Int) {
        if (!ScryingRenderTypes.isReady()) return
        val shader = ScryingRenderTypes.getShader() ?: return

        shader.safeGetUniform("ScreenSize").set(w.toFloat(), h.toFloat())
        val sessionTime = (Util.getMillis() / 1000.0 - sessionStartSeconds).toFloat()
        shader.safeGetUniform("SessionTime").set(sessionTime)

        graphics.flush()
        graphics.pose().pushPose()
        graphics.pose().translate(0.0, 0.0, VIGNETTE_Z)

        val source = graphics.bufferSource()
        val consumer = source.getBuffer(ScryingRenderTypes.VIGNETTE)
        val matrix = graphics.pose().last().pose()
        val wf = w.toFloat()
        val hf = h.toFloat()
        // CCW quad in the gui ortho — TL → BL → BR → TR matches GuiGraphics.fill order.
        consumer.vertex(matrix, 0f, 0f, 0f).endVertex()
        consumer.vertex(matrix, 0f, hf, 0f).endVertex()
        consumer.vertex(matrix, wf, hf, 0f).endVertex()
        consumer.vertex(matrix, wf, 0f, 0f).endVertex()
        source.endBatch(ScryingRenderTypes.VIGNETTE)

        graphics.pose().popPose()
    }

    /** Random Sselith dictionary words rendered in the SGA font, drifting along the
     *  vignette band. Each glyph's offset along its edge advances continuously with time
     *  (no bucketed reshuffle) so the runes slide rather than jump; the per-glyph hash
     *  picks a stable edge, word, and offset within the band. */
    /** SGA-glyph runes spawning at the perimeter, drifting toward the screen centre and
     *  fading as they go. Each glyph has a deterministic per-index seed for stable
     *  side / word / spawn-position / cycle-phase choices; the cycle clock advances
     *  every frame so individual runes appear at the edge, slide inward over their
     *  lifetime, and disappear before they reach the dead centre. */
    private fun renderSgaGlyphs(graphics: GuiGraphics, w: Int, h: Int) {
        val font = Minecraft.getInstance().font
        val tSec = Util.getMillis() / 1000.0
        val cx = w * 0.5
        val cy = h * 0.5
        val baseRgb = GLYPH_COLOR_ARGB and 0x00FFFFFF

        // High-z pose so the runes sit on top of every late vanilla composite pass.
        graphics.pose().pushPose()
        graphics.pose().translate(0.0, 0.0, GLYPH_Z)

        for (i in 0 until GLYPH_COUNT) {
            val hash = (i * 0x9E3779B97F4A7C15UL.toLong()) xor 0x5A5A5A5A5A5A5A5AL
            val side = ((hash ushr 60) and 0x3L).toInt()
            val wordIdx = ((hash ushr 48) and 0xFFFL).toInt() % SSELITH_WORDS.size
            // Where along this rune's spawn-edge it appears, [0, 1].
            val edgeFrac = ((hash ushr 32) and 0xFFFFL).toInt() / 65536.0
            // Per-rune lifespan jitter so they don't all live exactly GLYPH_LIFE_SECONDS.
            val lifeScale = 0.75 + ((hash ushr 16) and 0xFFL).toInt() / 255.0 * 0.5
            val lifeSec = GLYPH_LIFE_SECONDS * lifeScale
            // Per-rune phase offset so they don't all cycle in lockstep.
            val phaseSec = (hash and 0xFFFFL).toInt() / 65536.0 * lifeSec
            // Cycle progress in [0, 1) — 0 = just spawned at edge, → 1 = absorbed.
            val cycle = ((tSec + phaseSec) % lifeSec) / lifeSec
            val progress = cycle.toFloat()

            val component = Sga.obfuscate(SSELITH_WORDS[wordIdx])
            val textW = font.width(component)

            // Edge-spawn position. Each side anchors one coord and varies the other.
            val edgeX: Double
            val edgeY: Double
            when (side) {
                0 -> { edgeX = edgeFrac * (w - textW); edgeY = -font.lineHeight.toDouble() }   // top
                1 -> { edgeX = edgeFrac * (w - textW); edgeY = h.toDouble() }                   // bottom
                2 -> { edgeX = -textW.toDouble();      edgeY = edgeFrac * (h - font.lineHeight) } // left
                else -> { edgeX = w.toDouble();        edgeY = edgeFrac * (h - font.lineHeight) } // right
            }

            // Interpolate toward the screen centre. Stops at 0.92 of the way so glyphs
            // disappear into the central area rather than physically converging on a
            // single point — the convergence reads weirder than a fade-out shy of centre.
            val travel = (progress * 0.92).toDouble()
            val x = (edgeX + (cx - edgeX) * travel).toInt()
            val y = (edgeY + (cy - edgeY) * travel).toInt()

            // Alpha curve — tied to the inward drift instead of just the last sliver of
            // life. Quick fade-in over the first 8% so the spawn doesn't pop, then a
            // long fade-out from 15% to 85%: by the time the rune has drifted ~70% of
            // the way toward centre its alpha is already past 95% gone. The old window
            // (60-100%) kept the rune at full brightness across most of its journey,
            // which read as "not fading" — the fade was visually compressed into the
            // last fraction of a second of life right at the centre.
            val fadeIn = smoothstep01(0.00f, 0.08f, progress)
            val fadeOut = 1f - smoothstep01(0.15f, 0.85f, progress)
            val alpha = (fadeIn * fadeOut * 255f).toInt().coerceIn(0, 255)
            // [Font.adjustColor] snaps alphas with top 6 bits zero (i.e. < 4) back to
            // fully opaque — so an alpha of 3 would render at full brightness, the
            // exact opposite of what the fade is trying to do. Skip the draw entirely
            // below that threshold; alpha=4 is already ~1.6% so the cutoff is invisible.
            if (alpha < 4) continue
            val colorWithAlpha = (alpha shl 24) or baseRgb
            graphics.drawString(font, component, x, y, colorWithAlpha, false)
        }

        // Flush so the queued vertices draw at our high z before the pose pops.
        graphics.flush()
        graphics.pose().popPose()
    }

    /** Standard smoothstep, [0, 1] output. Inlined because the kotlin.math one
     *  doesn't exist and importing JOML for one helper is overkill. */
    private fun smoothstep01(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Curated Sselith vocabulary pulled from `texts/sselith/dictionary/sselith_dictionary.json`.
     *  Short-to-medium words only so each render is a tight rune cluster that doesn't run
     *  the full width of the screen. The dictionary itself is huge (~3K entries); we don't
     *  load it at runtime because the overlay only needs a flavor sample and the JSON is a
     *  build-time asset, not a per-frame lookup. */
    private val SSELITH_WORDS: Array<String> = arrayOf(
        "aevkh", "aevkhegh", "aevocht", "aevust", "akh", "akkumulaskhun",
        "ambiensth", "amielegh", "amielkarn", "ankessthri", "ankhelfish",
        "apprentikh", "arbalistkh", "armkhvel", "augukh", "autoskave",
        "avakenelk", "azhtek", "banankh", "barokvekh", "ontnemkh",
        "kwevkust", "kwevegh", "morontkr", "ontpartkhust", "yghann",
        "yghannmornkt", "zhe", "kre", "vel", "mor", "ont", "toch",
        "vraestmorocht", "skarn", "schest", "moroch", "kelkargh",
        "elksvel", "thauntakh", "morakht", "vraestakh", "kwevakht",
    )

    /** Bright warm yellow for the SGA runes — #E8C84A, full alpha. Punched up from the
     *  earlier olive (#B8A03A) so the runes clearly pop on top of the brown vignette. */
    private val GLYPH_COLOR_ARGB: Int = 0xFFE8C84A.toInt()

    /** Fraction of the shorter screen dimension used as the "glyph band" around the
     *  vignette edge — picked to roughly match the visible noise-modulated vignette ring
     *  in the shader. */
    private const val VIGNETTE_DEPTH_FRAC: Float = 0.18f

    /** GUI ortho-projection Z translation for the vignette quad. 500 keeps it in front of
     *  container slot items (z=100) and tooltips (z=400) — matches the Ygann overlay's
     *  OVERLAY_Z so the two coexist cleanly when both are active. */
    private const val VIGNETTE_Z: Double = 500.0

    /** Z for the SGA glyph pass. Above the vignette (500), above tooltips (400), above
     *  vanilla's late composite layers — guarantees the runes are on top regardless of
     *  ordering quirks in vanilla's `Gui.render` body. */
    private const val GLYPH_Z: Double = 900.0

    /** Number of Sselith glyph words rendered per frame. */
    private const val GLYPH_COUNT: Int = 20

    /** How long each rune lives — from spawning at the edge to fully fading out partway
     *  to centre. Per-rune jitter (`lifeScale`) widens this to ~0.75×–1.25× so they don't
     *  cycle in unison. 7 seconds gives a calm, ambient cadence. */
    private const val GLYPH_LIFE_SECONDS: Double = 7.0

    /** Convert a shipyard `BlockPos` to its current world position using the VS2 client
     *  render transform when the block sits on a ship; identity (+ block centre) for
     *  world blocks. Mirrors [org.shipwrights.enderkinesis.client.OrbClientGeometry] but
     *  without the active-face offset — for the camera, the geometric block centre is the
     *  right reference point. */
    private fun shipyardToWorld(level: ClientLevel, pos: BlockPos): Vec3? {
        val ship = level.getShipManagingPos(pos)
        val v = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        when (ship) {
            is ClientShip -> ship.renderTransform.shipToWorld.transformPosition(v)
            null -> { /* world block — keep the block-centre coords */ }
            else -> ship.shipToWorld.transformPosition(v)
        }
        return Vec3(v.x, v.y, v.z)
    }
}
