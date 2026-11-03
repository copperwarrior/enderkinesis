package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.SselithChatTeleport.ReturnPoint
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import org.shipwrights.enderkinesis.item.WeheloBlade
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.SpellSounds
import java.util.UUID

/** A Wehelo Blade tear. Any entity that touches it goes through until it closes. */
class WeheloTearEntity(type: EntityType<out WeheloTearEntity>, level: Level) : Entity(type, level) {

    private var owner: UUID? = null
    private var exit = false
    /** For an exit tear, the destination. For an entry tear, where a player who goes in returns to. */
    private var target: ReturnPoint? = null

    init {
        noPhysics = true
    }

    internal fun setup(owner: UUID, exit: Boolean, target: ReturnPoint) {
        this.owner = owner
        this.exit = exit
        this.target = target
    }

    override fun defineSynchedData() {}

    override fun tick() {
        super.tick()
        if (level().isClientSide) return
        val owner = owner
        val target = target
        if (owner == null || target == null || tickCount >= LIFE_TICKS) {
            discard()
            return
        }
        val level = level() as ServerLevel
        if (tickCount == LIFE_TICKS - CLOSE_TICKS) SpellSounds.play(level, position(), EKSounds.WEHELO_TEAR_CLOSE)
        for (entity in level.getEntities(this, boundingBox, ::canPass)) {
            // The move plays the second pass at the destination.
            val moved = if (exit) WeheloPocket.leave(entity, target) else WeheloPocket.enter(entity, owner, target)
            if (moved) SpellSounds.play(level, position(), EKSounds.WEHELO_PASS) else entity.setPortalCooldown()
        }
    }

    private fun canPass(entity: Entity): Boolean =
        entity !is WeheloTearEntity && entity.isAlive && !entity.isSpectator && !entity.isPassenger &&
            !entity.isOnPortalCooldown && entity.canChangeDimensions() && !entity.type.`is`(WeheloBlade.UNSHIFTABLE)

    override fun isPickable(): Boolean = false

    override fun readAdditionalSaveData(tag: CompoundTag) {}

    override fun addAdditionalSaveData(tag: CompoundTag) {}

    companion object {
        const val ID_PATH = "wehelo_tear"
        const val LIFE_TICKS = 200
        /** The loop stops and the close plays this many ticks before the end. */
        const val CLOSE_TICKS = 16
        const val HEIGHT = 2.6f
    }
}
