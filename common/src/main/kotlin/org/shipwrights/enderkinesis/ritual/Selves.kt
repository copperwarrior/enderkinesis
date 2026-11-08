package org.shipwrights.enderkinesis.ritual

import com.google.gson.JsonObject
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import java.util.UUID
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.FlyingMob
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.animal.FlyingAnimal
import net.minecraft.world.entity.monster.Spider
import net.minecraft.world.entity.monster.Vex
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Ritual of Selves: the minds of the creatures within [radius] move into each other's bodies. */
class SwapSelves(private val radius: Double) : RitualEffect {
    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? =
        if (Selves.swap(context.level, Vec3.atBottomCenterOf(context.center), radius)) ItemStack.EMPTY else null

    companion object {
        private const val DEFAULT_RADIUS = 3.5
        private const val MAX_RADIUS = 8.0

        fun parse(json: JsonObject): SwapSelves? {
            val radius = json.get("radius")?.asDouble ?: DEFAULT_RADIUS
            return if (radius > 0.0 && radius <= MAX_RADIUS) SwapSelves(radius) else null
        }
    }
}

/** The body a player's mind is in. A player in their own body has no form. */
sealed interface Form {
    /** The body of the player [owner]. */
    data class Of(val owner: UUID) : Form

    /** A vanilla creature's body, with its saved data so it comes back as it was. */
    class Creature(val type: EntityType<*>, val tag: CompoundTag) : Form
}

/**
 * Bodies stay where they stand and minds move. A creature's mind in a player body is that creature itself, so its
 * vanilla AI runs unchanged; [SelvesBodies] gives it the player's shape. Items stay with the body and are kept here.
 */
object Selves {

    val S2C_FORMS: ResourceLocation = EnderkinesisMod.id("ritual/selves")

    private const val DATA_NAME = "enderkinesis_selves"
    private const val MAX_VESSELS = 16
    private const val VESSEL_HEIGHT = 2.0
    private const val PLAYER_HEALTH = 20.0
    const val PLAYER_SPEED = 0.1
    // A player's input is 0.98 and a mob's is its speed, so a mob's walk is speed² where a player's is 0.98 × speed.
    const val PLAYER_INPUT = 0.98
    val HEALTH_ID: UUID = UUID.fromString("5b1f3e0c-6a1d-4c39-9d43-6f0b6ad1e201")
    val SPEED_ID: UUID = UUID.fromString("5b1f3e0c-6a1d-4c39-9d43-6f0b6ad1e202")
    const val TAG_ITEMS = "Items"
    const val TAG_SELECTED = "Selected"
    private val BOSSES: Set<EntityType<*>> = setOf(EntityType.ENDER_DRAGON, EntityType.WITHER)

    // Server thread only; null while no server runs.
    @Volatile internal var serverData: Data? = null
        private set

    // Render thread only.
    @Volatile private var clientForms: Map<UUID, Form> = emptyMap()
    @Volatile internal var clientBodies: Map<UUID, UUID> = emptyMap()
        private set
    private val clientDummies = Dummies()

    fun init() {
        LifecycleEvent.SERVER_STARTED.register { server -> serverData = data(server) }
        LifecycleEvent.SERVER_STOPPED.register { _ -> serverData = null }
        PlayerEvent.PLAYER_JOIN.register { player ->
            val data = data(player.server)
            send(player, data)
            fit(player, data.forms[player.uuid])
        }
        PlayerEvent.PLAYER_RESPAWN.register { player, conqueredEnd -> onRespawn(player, conqueredEnd) }
    }

    fun formOf(player: Player): Form? =
        if (player.level().isClientSide) clientForms[player.uuid] else serverData?.forms?.get(player.uuid)

    /** A creature that is never in a level, to read the size, eye height and traits of [player]'s creature form. */
    fun dummyOf(player: Player): LivingEntity? {
        val form = formOf(player) as? Form.Creature ?: return null
        val level = player.level()
        val dummies = if (level.isClientSide) clientDummies else serverData?.dummies ?: return null
        return dummies.get(level, player.uuid, form)
    }

    @JvmStatic
    fun dimensions(player: Player, vanilla: EntityDimensions): EntityDimensions =
        dummyOf(player)?.getDimensions(Pose.STANDING) ?: vanilla

    @JvmStatic
    fun eyeHeight(player: Player, vanilla: Float): Float = dummyOf(player)?.getEyeHeight(Pose.STANDING) ?: vanilla

    @JvmStatic
    fun breathesWater(entity: LivingEntity, vanilla: Boolean): Boolean =
        vanilla || (entity is Player && dummyOf(entity)?.canBreatheUnderwater() == true)

    @JvmStatic
    fun fireImmune(player: Player, vanilla: Boolean): Boolean = vanilla || dummyOf(player)?.fireImmune() == true

    @JvmStatic
    fun climbs(entity: LivingEntity, vanilla: Boolean): Boolean =
        vanilla || (entity is Player && entity.horizontalCollision && dummyOf(entity) is Spider)

    /** Creature bodies have no hands. */
    @JvmStatic
    fun handless(player: Player): Boolean = formOf(player) is Form.Creature

    private fun flies(dummy: LivingEntity): Boolean = dummy is FlyingAnimal || dummy is FlyingMob || dummy is Bat || dummy is Vex

    private fun isVessel(entity: LivingEntity): Boolean = when (entity) {
        is ServerPlayer -> !entity.isSpectator
        is Mob -> entity.type !in BOSSES && BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).namespace == "minecraft"
        else -> false
    }

    /** False when fewer than two creatures, or no player, stand within [radius]. */
    fun swap(level: ServerLevel, center: Vec3, radius: Double): Boolean {
        val box = AABB(center, center).inflate(radius, VESSEL_HEIGHT, radius)
        val vessels = level.getEntitiesOfClass(LivingEntity::class.java, box) {
            it.isAlive && isVessel(it) && it.position().subtract(center).horizontalDistanceSqr() <= radius * radius
        }.take(MAX_VESSELS)
        if (vessels.size < 2 || vessels.none { it is ServerPlayer }) return false
        val data = data(level.server)
        val shells = vessels.map { shellOf(it, data) }
        // Sattolo's shuffle: one cycle through every vessel, so no mind stays in its own body.
        val target = IntArray(shells.size) { it }
        for (i in target.size - 1 downTo 1) {
            val j = level.random.nextInt(i)
            target[i] = target[j].also { target[j] = target[i] }
        }
        for (vessel in vessels) if (vessel !is ServerPlayer) vessel.discard()
        shells.forEachIndexed { i, shell ->
            val into = shells[target[i]]
            val player = shell.player
            if (player != null) enter(player, into, data) else materialize(level, into, checkNotNull(shell.mind), data)
        }
        data.setDirty()
        broadcast(level.server)
        return true
    }

    /** A creature's mind: the creature's own saved data, so a tame wolf or a villager with trades stays itself. */
    private class Mind(val type: EntityType<*>, val tag: CompoundTag)

    private class Shell(
        val pos: Vec3, val yRot: Float, val xRot: Float, val form: Form, val items: CompoundTag, val health: Float,
        val player: ServerPlayer?, val mind: Mind?,
    )

    private fun shellOf(entity: LivingEntity, data: Data): Shell {
        if (entity is ServerPlayer) {
            val form = data.forms[entity.uuid] ?: Form.Of(entity.uuid)
            // A creature body cannot carry items, so what it got (from /give or crafting) falls where it stands.
            if (form is Form.Creature) entity.inventory.dropAll()
            return Shell(entity.position(), entity.yRot, entity.xRot, form, takeItems(entity), entity.health, entity, null)
        }
        val tag = entity.saveWithoutId(CompoundTag())
        val body = data.bodies.remove(entity.uuid)
        val form = if (body != null) Form.Of(body.owner) else Form.Creature(entity.type, tag)
        val items = body?.items ?: CompoundTag()
        return Shell(entity.position(), entity.yRot, entity.xRot, form, items, entity.health, null, Mind(entity.type, tag))
    }

    private fun takeItems(player: ServerPlayer): CompoundTag {
        val tag = CompoundTag()
        tag.put(TAG_ITEMS, player.inventory.save(ListTag()))
        tag.putInt(TAG_SELECTED, player.inventory.selected)
        player.inventory.clearContent()
        return tag
    }

    private fun giveItems(player: ServerPlayer, items: CompoundTag) {
        player.inventory.load(items.getList(TAG_ITEMS, Tag.TAG_COMPOUND.toInt()))
        if (items.contains(TAG_SELECTED)) player.inventory.selected = items.getInt(TAG_SELECTED)
    }

    private fun enter(player: ServerPlayer, shell: Shell, data: Data) {
        player.teleportTo(player.serverLevel(), shell.pos.x, shell.pos.y, shell.pos.z, shell.yRot, shell.xRot)
        val form = shell.form.takeUnless { it is Form.Of && it.owner == player.uuid }
        if (form == null) data.forms.remove(player.uuid) else data.forms[player.uuid] = form
        giveItems(player, shell.items)
        fit(player, form)
        player.health = shell.health
    }

    private fun materialize(level: ServerLevel, shell: Shell, mind: Mind, data: Data) {
        val form = shell.form
        // Two creatures that trade bodies act as the body's kind, so only the body comes back.
        val source = if (form is Form.Creature) Mind(form.type, form.tag) else mind
        val body = source.type.create(level) as? LivingEntity ?: return
        body.load(source.tag)
        body.setUUID(UUID.randomUUID())
        if (form is Form.Of && body is Mob) {
            data.bodies[body.uuid] = Data.Body(form.owner, shell.items)
            SelvesBodies.wear(body, shell.items)
        }
        body.moveTo(shell.pos.x, shell.pos.y, shell.pos.z, shell.yRot, shell.xRot)
        body.yHeadRot = shell.yRot
        body.yBodyRot = shell.yRot
        body.health = shell.health
        body.refreshDimensions()
        level.addFreshEntity(body)
    }

    /** Sets [player]'s health, speed, flight and size for [form]. */
    private fun fit(player: ServerPlayer, form: Form?) {
        val maxHealth = checkNotNull(player.getAttribute(Attributes.MAX_HEALTH))
        val speed = checkNotNull(player.getAttribute(Attributes.MOVEMENT_SPEED))
        maxHealth.removeModifier(HEALTH_ID)
        speed.removeModifier(SPEED_ID)
        player.gameMode.gameModeForPlayer.updatePlayerAbilities(player.abilities)
        val dummy = if (form is Form.Creature) dummyOf(player) else null
        if (dummy != null) {
            maxHealth.addPermanentModifier(
                AttributeModifier(HEALTH_ID, "Ritual of Selves", dummy.maxHealth - PLAYER_HEALTH, AttributeModifier.Operation.ADDITION),
            )
            val walk = dummy.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue
            if (walk != null) {
                // Not faster than a player walks: some mobs (villagers) have a high base and walk at a fraction of it.
                val asPlayer = (walk * walk / PLAYER_INPUT).coerceAtMost(PLAYER_SPEED)
                speed.addPermanentModifier(
                    AttributeModifier(SPEED_ID, "Ritual of Selves", asPlayer / PLAYER_SPEED - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL),
                )
            }
            if (flies(dummy)) player.abilities.mayfly = true
        }
        player.onUpdateAbilities()
        player.health = player.health.coerceAtMost(player.maxHealth)
        player.refreshDimensions()
    }

    /** A death ends the form, and the player's own body comes back to them with its items. */
    private fun onRespawn(player: ServerPlayer, conqueredEnd: Boolean) {
        val data = data(player.server)
        if (conqueredEnd || data.forms.remove(player.uuid) == null) {
            fit(player, data.forms[player.uuid])
            return
        }
        val own = data.bodies.entries.firstOrNull { it.value.owner == player.uuid }
        if (own != null) {
            data.bodies.remove(own.key)
            giveItems(player, own.value.items)
            val entity = player.server.allLevels.firstNotNullOfOrNull { it.getEntity(own.key) }
            if (entity != null) entity.discard() else data.reclaimed.add(own.key)
        }
        data.setDirty()
        fit(player, null)
        broadcast(player.server)
    }

    internal fun broadcast(server: MinecraftServer) {
        val data = data(server)
        for (player in server.playerList.players) send(player, data)
    }

    private fun send(player: ServerPlayer, data: Data) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(data.forms.size)
        for ((uuid, form) in data.forms) {
            buf.writeUUID(uuid)
            when (form) {
                is Form.Of -> {
                    buf.writeBoolean(false)
                    buf.writeUUID(form.owner)
                }
                is Form.Creature -> {
                    buf.writeBoolean(true)
                    buf.writeResourceLocation(BuiltInRegistries.ENTITY_TYPE.getKey(form.type))
                    buf.writeNbt(form.tag)
                }
            }
        }
        buf.writeVarInt(data.bodies.size)
        for ((entity, body) in data.bodies) {
            buf.writeUUID(entity)
            buf.writeUUID(body.owner)
        }
        NetworkManager.sendToPlayer(player, S2C_FORMS, buf)
    }

    /** Client: reads a [send] packet off the network thread; pass the result to [receive] on the render thread. */
    fun read(buf: FriendlyByteBuf): Pair<Map<UUID, Form>, Map<UUID, UUID>> {
        val forms = HashMap<UUID, Form>()
        repeat(buf.readVarInt()) {
            val uuid = buf.readUUID()
            forms[uuid] = if (buf.readBoolean()) {
                Form.Creature(BuiltInRegistries.ENTITY_TYPE.get(buf.readResourceLocation()), buf.readNbt() ?: CompoundTag())
            } else {
                Form.Of(buf.readUUID())
            }
        }
        val bodies = HashMap<UUID, UUID>()
        repeat(buf.readVarInt()) { bodies[buf.readUUID()] = buf.readUUID() }
        return forms to bodies
    }

    fun receive(state: Pair<Map<UUID, Form>, Map<UUID, UUID>>) {
        clientForms = state.first
        clientBodies = state.second
    }

    private fun data(server: MinecraftServer): Data =
        server.overworld().dataStorage.computeIfAbsent(Data::load, ::Data, DATA_NAME)

    /** One dummy creature for each player in a creature form, made again when the form or level changes. */
    internal class Dummies {
        private val byPlayer = HashMap<UUID, Pair<Form.Creature, LivingEntity>>()

        fun get(level: Level, player: UUID, form: Form.Creature): LivingEntity? {
            byPlayer[player]?.let { (f, dummy) -> if (f === form && dummy.level() === level) return dummy }
            val dummy = form.type.create(level) as? LivingEntity ?: return null
            dummy.load(form.tag)
            byPlayer[player] = form to dummy
            return dummy
        }
    }

    internal class Data : SavedData() {
        class Body(val owner: UUID, val items: CompoundTag)

        val forms = HashMap<UUID, Form>()
        /** Player bodies with a creature's mind in them, by the creature's entity UUID. */
        val bodies = HashMap<UUID, Body>()
        /** Bodies their owner took back on death while their chunk was not loaded; they go when they next tick. */
        val reclaimed = HashSet<UUID>()
        val dummies = Dummies()

        override fun save(tag: CompoundTag): CompoundTag = tag.apply {
            put("forms", ListTag().apply {
                for ((uuid, form) in forms) add(CompoundTag().apply {
                    putUUID("player", uuid)
                    when (form) {
                        is Form.Of -> putUUID("owner", form.owner)
                        is Form.Creature -> {
                            putString("type", BuiltInRegistries.ENTITY_TYPE.getKey(form.type).toString())
                            put("tag", form.tag)
                        }
                    }
                })
            })
            put("bodies", ListTag().apply {
                for ((id, body) in bodies) add(CompoundTag().apply {
                    putUUID("id", id)
                    putUUID("owner", body.owner)
                    put("items", body.items)
                })
            })
            put("reclaimed", ListTag().apply { for (id in reclaimed) add(CompoundTag().apply { putUUID("id", id) }) })
        }

        companion object {
            fun load(tag: CompoundTag): Data = Data().apply {
                for (entry in tag.getList("forms", Tag.TAG_COMPOUND.toInt())) {
                    val e = entry as CompoundTag
                    val form = if (e.hasUUID("owner")) {
                        Form.Of(e.getUUID("owner"))
                    } else {
                        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse(e.getString("type"))).orElse(null)
                            ?: continue
                        Form.Creature(type, e.getCompound("tag"))
                    }
                    forms[e.getUUID("player")] = form
                }
                for (entry in tag.getList("bodies", Tag.TAG_COMPOUND.toInt())) {
                    val e = entry as CompoundTag
                    bodies[e.getUUID("id")] = Body(e.getUUID("owner"), e.getCompound("items"))
                }
                for (entry in tag.getList("reclaimed", Tag.TAG_COMPOUND.toInt())) reclaimed.add((entry as CompoundTag).getUUID("id"))
            }
        }
    }
}
