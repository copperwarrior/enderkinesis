package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.body.GamylanCageNetwork

/** Client-side: receivers for the cage S2C channels + the
 *  [sendAttack] C2S helper used by [GamylanCageRaytrace] when the
 *  player's swing lands a hit on a cage body. */
object GamylanCageNetworkClient {

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, GamylanCageNetwork.CAGE_ADDED) { buf, _ ->
            val bodyId = buf.readLong()
            val captured = buf.readNbt() ?: return@registerReceiver
            val animation = buf.readNbt() ?: return@registerReceiver
            val origin = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val hover = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val shapeDims = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val addedAt = buf.readLong()
            val maxHp = buf.readVarInt()
            val hp = buf.readVarInt()
            val hasPlayer = buf.readBoolean()
            val playerUuid: java.util.UUID?
            val playerName: String?
            val playerCustomization: Byte
            val playerEquipment: List<net.minecraft.world.item.ItemStack>
            if (hasPlayer) {
                playerUuid = buf.readUUID()
                playerName = buf.readUtf()
                playerCustomization = buf.readByte()
                val slotCount = buf.readVarInt()
                playerEquipment = List(slotCount) { buf.readItem() }
            } else {
                playerUuid = null
                playerName = null
                playerCustomization = 0
                playerEquipment = emptyList()
            }
            val displayedItem = buf.readItem()
            Minecraft.getInstance().execute {
                val dim = Minecraft.getInstance().level?.dimension() ?: return@execute
                val entry = GamylanCageClientRegistry.Entry(
                    bodyId, captured, animation,
                    origin, hover, shapeDims, addedAt, maxHp, hp,
                    playerUuid, playerName,
                    playerCustomization, playerEquipment,
                    displayedItem,
                )
                GamylanCageClientRegistry.add(dim, entry)
                if (playerUuid != null && playerName != null) {
                    val ghost = GamylanCageClientRegistry.renderEntityFor(entry)
                    if (ghost is net.minecraft.client.player.AbstractClientPlayer) {
                        installPlayerInfo(ghost, playerUuid, playerName, entry)
                    }
                }
            }
        }

        NetworkManager.registerReceiver(NetworkManager.Side.S2C, GamylanCageNetwork.CAGE_REMOVED) { buf, _ ->
            val bodyId = buf.readLong()
            Minecraft.getInstance().execute {
                val mc = Minecraft.getInstance()
                val dim = mc.level?.dimension() ?: return@execute
                val entry = GamylanCageClientRegistry.entryFor(dim, bodyId)
                if (entry != null) seedShatterFor(entry)
                GamylanCageClientRegistry.remove(dim, bodyId)
            }
        }

        NetworkManager.registerReceiver(NetworkManager.Side.S2C, GamylanCageNetwork.CAGE_HP) { buf, _ ->
            val bodyId = buf.readLong()
            val hp = buf.readVarInt()
            Minecraft.getInstance().execute {
                val dim = Minecraft.getInstance().level?.dimension() ?: return@execute
                GamylanCageClientRegistry.setHp(dim, bodyId, hp)
            }
        }

        NetworkManager.registerReceiver(NetworkManager.Side.S2C, GamylanCageNetwork.CAGE_IMPACT) { buf, _ ->
            val bodyId = buf.readLong()
            Minecraft.getInstance().execute {
                val mc = Minecraft.getInstance()
                val dim = mc.level?.dimension() ?: return@execute
                val entry = GamylanCageClientRegistry.entryFor(dim, bodyId) ?: return@execute
                spawnGlassBreakBurst(entry)
                mc.level?.playLocalSound(
                    entry.hoverPos.x, entry.hoverPos.y, entry.hoverPos.z,
                    net.minecraft.sounds.SoundEvents.GLASS_BREAK,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    0.35f, 1.4f, false,
                )
            }
        }
    }

    /** A burst of vanilla glass block-break particles at the cage's
     *  live position, scaled to the crystal's live half-extents. Uses
     *  [net.minecraft.core.particles.BlockParticleOption] with the
     *  vanilla `glass` state so each particle picks up a glass fragment
     *  texture. Way lighter than the shatter-cube effect — this is the
     *  "took a hit" cue, not the "broke apart" cue. */
    private fun spawnGlassBreakBurst(entry: GamylanCageClientRegistry.Entry) {
        val level = Minecraft.getInstance().level ?: return
        val livePos = GamylanCageClientRegistry.livePosition(entry)
        val liveScale = GamylanCageClientRegistry.liveScale(entry).toDouble()
        val halfX = entry.shapeDims.x * liveScale
        val halfY = (entry.shapeDims.y + entry.shapeDims.z) * liveScale
        val halfZ = entry.shapeDims.x * liveScale
        val rand = level.random
        val option = net.minecraft.core.particles.BlockParticleOption(
            net.minecraft.core.particles.ParticleTypes.BLOCK,
            net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(),
        )
        repeat(GLASS_BREAK_PARTICLE_COUNT) {
            val px = livePos.x + (rand.nextDouble() - 0.5) * 2.0 * halfX
            val py = livePos.y + (rand.nextDouble() - 0.5) * 2.0 * halfY
            val pz = livePos.z + (rand.nextDouble() - 0.5) * 2.0 * halfZ
            val vx = (rand.nextDouble() - 0.5) * 0.4
            val vy = rand.nextDouble() * 0.25
            val vz = (rand.nextDouble() - 0.5) * 0.4
            level.addParticle(option, px, py, pz, vx, vy, vz)
        }
    }

    /** Fragments per impact burst — same order of magnitude as vanilla
     *  block-break emits (`~12` for a broken glass block). */
    private const val GLASS_BREAK_PARTICLE_COUNT: Int = 12

    /** Install a [net.minecraft.client.multiplayer.PlayerInfo] on the
     *  ghost's `playerInfo` field. Two paths:
     *
     *   1. If the client's connection still has a matching PlayerInfo
     *      (the victim is online, or the entry was just added by an
     *      in-session capture), we reuse it — `PlayerInfo.getSkin`
     *      lazily fetches the skin from the client's SkinManager, and
     *      the profile there already has the `textures` property.
     *   2. Otherwise (world reload, offline victim) we bootstrap from a
     *      partial `GameProfile(uuid, name)`. `SkullBlockEntity.updateGameprofile`
     *      asynchronously resolves the profile against Mojang's session
     *      service (client-side; `SkullBlockEntity.setup` is called from
     *      both server + client init in 1.20.1), then we hand the
     *      textures-filled profile to a new PlayerInfo and set it on
     *      the ghost. The ghost's next render triggers the skin
     *      download via vanilla's SkinManager.
     *
     *  Writing the field goes through
     *  [AbstractClientPlayerCagePlayerInfoAccessor] (mixin `@Accessor`)
     *  because [net.minecraft.client.player.AbstractClientPlayer.playerInfo]
     *  is protected and there's no vanilla setter. */
    private fun installPlayerInfo(
        ghost: net.minecraft.client.player.AbstractClientPlayer,
        uuid: java.util.UUID,
        name: String,
        entry: GamylanCageClientRegistry.Entry,
    ) {
        val accessor = ghost as? org.shipwrights.enderkinesis.mixin.AbstractClientPlayerCagePlayerInfoAccessor
            ?: return
        val connCached = Minecraft.getInstance().connection?.getPlayerInfo(uuid)
        if (connCached != null) {
            accessor.`enderkinesis$setPlayerInfo`(connCached)
            fetchSkinColorAsync(connCached.profile, entry)
            return
        }
        val partial = com.mojang.authlib.GameProfile(uuid, name)
        net.minecraft.world.level.block.entity.SkullBlockEntity.updateGameprofile(partial) { filled ->
            Minecraft.getInstance().execute {
                if (filled == null) return@execute
                val info = net.minecraft.client.multiplayer.PlayerInfo(filled, false)
                accessor.`enderkinesis$setPlayerInfo`(info)
                fetchSkinColorAsync(filled, entry)
            }
        }
    }

    /** Resolve the player's skin through vanilla's [net.minecraft.client.resources.SkinManager]
     *  pipeline (`registerSkins` → `HttpTexture` download into the
     *  shared on-disk skin cache → callback), then sample the cached
     *  PNG's highest-chroma pixel into
     *  [GamylanCageClientRegistry.Entry.overrideCrystalColor]. Exactly
     *  the path player heads take, so:
     *   - a skin MC already has cached (victim visible in-world, or a
     *     previous session) is reused, not re-downloaded;
     *   - the download shares vanilla's executor, proxy, and cache
     *     layout instead of a hand-rolled HttpURLConnection.
     *
     *  The cache file location mirrors `SkinManager.registerTexture`:
     *  `skinsDirectory / sha1(hash)[0..2] / sha1(hash)` where `hash` is
     *  [MinecraftProfileTexture.getHash]. By callback time HttpTexture
     *  has written the raw PNG there (or it already existed). File read
     *  + pixel scan run off-thread; failures leave the Wohlon fallback. */
    private fun fetchSkinColorAsync(profile: com.mojang.authlib.GameProfile, entry: GamylanCageClientRegistry.Entry) {
        val skinManager = Minecraft.getInstance().skinManager ?: return
        skinManager.registerSkins(profile, { type, _, texture ->
            if (type != com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) return@registerSkins
            val skinsDir = (skinManager as? org.shipwrights.enderkinesis.mixin.SkinManagerCageDirectoryAccessor)
                ?.`enderkinesis$getSkinsDirectory`() ?: return@registerSkins
            val sha1 = com.google.common.hash.Hashing.sha1()
                .hashUnencodedChars(texture.hash).toString()
            val cached = java.io.File(java.io.File(skinsDir, sha1.substring(0, 2)), sha1)
            java.util.concurrent.CompletableFuture.runAsync {
                try {
                    cached.inputStream().use { stream ->
                        val color = GamylanCageClientRegistry.sampleMostSaturatedFromStream(stream)
                        if (color != null) entry.overrideCrystalColor = color
                    }
                } catch (_: Throwable) { /* fallback stays */ }
            }
        }, false)
    }

    /** Sent by [GamylanCageRaytrace] after the client raycast finds a
     *  cage in the player's look direction (or by the attack mixin
     *  passing through). Server validates range + applies damage. */
    fun sendAttack(bodyId: Long) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        NetworkManager.sendToServer(GamylanCageNetwork.CAGE_ATTACK, buf)
    }

    /** Spawn a [GamylanCageShatter] cloud at the entry's live world
     *  pose. Colour picks up Wohlon blue for the crystal and orange for
     *  the Tribulla bottle; half-extents come from the cage's shape
     *  dims (already padded for the entity AABB) scaled by the live
     *  Staff-of-Scales factor. */
    private fun seedShatterFor(entry: GamylanCageClientRegistry.Entry) {
        val livePos = GamylanCageClientRegistry.livePosition(entry)
        val liveRot = GamylanCageClientRegistry.liveRotation(entry)
        val liveScale = GamylanCageClientRegistry.liveScale(entry).toDouble()
        val ghost = GamylanCageClientRegistry.renderEntityFor(entry)
        val isTribulla = ghost?.customName?.string == "Tribulla"
        val faceR: Int; val faceG: Int; val faceB: Int
        val lineR: Int; val lineG: Int; val lineB: Int
        if (isTribulla) {
            faceR = 244; faceG = 109; faceB = 45    // bottle orange
            lineR = 244; lineG = 109; lineB = 45
        } else {
            // Crystal shatter inherits the entry's derived crystal
            // colour (spawn-egg brightest / texture-max-sat / Wohlon
            // fallback) so a green zombie explodes into green shards,
            // a red bee cage into red shards, etc. Edge lines get the
            // same colour lifted 40% toward white so the outline still
            // reads brighter than the faces.
            val c = entry.crystalColor
            faceR = (c shr 16) and 0xFF
            faceG = (c shr 8) and 0xFF
            faceB = c and 0xFF
            lineR = (faceR + (255 - faceR) * 4 / 10).coerceIn(0, 255)
            lineG = (faceG + (255 - faceG) * 4 / 10).coerceIn(0, 255)
            lineB = (faceB + (255 - faceB) * 4 / 10).coerceIn(0, 255)
        }
        // shapeDims = (rx, midH, tipLen); the crystal extends ±rx in
        // X/Z and ±(midH + tipLen) in Y. liveScale folds in the Staff
        // of Scales so a shrunken cage shatters into a proportionally
        // smaller cloud.
        val halfX = entry.shapeDims.x * liveScale
        val halfY = (entry.shapeDims.y + entry.shapeDims.z) * liveScale
        val halfZ = entry.shapeDims.x * liveScale
        GamylanCageShatter.spawn(
            livePos.x, livePos.y, livePos.z,
            halfX, halfY, halfZ,
            liveRot,
            faceR, faceG, faceB,
            lineR, lineG, lineB,
        )
    }
}
