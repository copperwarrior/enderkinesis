package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.block.ScribesDeskNetwork
import org.shipwrights.enderkinesis.client.BookshelfPageClient
import org.shipwrights.enderkinesis.dimension.SselithParallel
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.sselith.BookshelfPage
import org.shipwrights.enderkinesis.sselith.SselithDustBurst
import kotlin.math.cos
import kotlin.math.sin

/**
 * Saves the text of a Sselith Bookshelf's page. A tap reads it; releasing a draw held for [CHARGE_TICKS] or more
 * carries the user to the one location that holds that text.
 */
class EideticBookmarkItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        if (level.dimension() != SselithRepertory.LEVEL_KEY) return InteractionResult.PASS
        if (!level.getBlockState(pos).`is`(EKBlocks.SSELITH_BOOKSHELF.get())) return InteractionResult.PASS
        val player = context.player
        if (player is ServerPlayer) {
            // Built on the server from the player's parallel, so a client cannot write its own text.
            context.itemInHand.getOrCreateTag().putString(TAG, BookshelfPage.text(pos.x, pos.y, pos.z, SselithParallel.get(player)))
            player.displayClientMessage(Component.translatable(SAVED_KEY), true)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (saved(stack) == null) return InteractionResultHolder.pass(stack)
        player.startUsingItem(hand)
        if (!level.isClientSide) {
            level.playSound(null, player.x, player.y, player.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 1f, 1f)
        }
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.BOW

    override fun getUseDuration(stack: ItemStack): Int = USE_DURATION

    override fun onUseTick(level: Level, user: LivingEntity, stack: ItemStack, remainingTicks: Int) {
        if (level is ServerLevel) spawnRing(level, user, getUseDuration(stack) - remainingTicks)
    }

    override fun releaseUsing(stack: ItemStack, level: Level, user: LivingEntity, timeLeft: Int) {
        val used = getUseDuration(stack) - timeLeft
        when {
            used < TAP_TICKS -> if (level.isClientSide) saved(stack)?.let(BookshelfPageClient::openScroll)
            used >= CHARGE_TICKS && user is ServerPlayer -> {
                val page = saved(stack)
                if (page != null && travel(user, page)) {
                    user.level().playSound(null, user.x, user.y, user.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f)
                }
                user.cooldowns.addCooldown(this, COOLDOWN_TICKS)
            }
        }
    }

    /** Rising yellow motes in a ring at the user's feet, thicker as the draw nears full charge. */
    private fun spawnRing(level: ServerLevel, user: LivingEntity, used: Int) {
        val count = RING_MIN_MOTES + minOf(used, CHARGE_TICKS) / RING_GROWTH_TICKS
        for (i in 0 until count) {
            val angle = used * RING_SPIN + i * (2.0 * Math.PI / count)
            // A count of 0 makes the packet carry (0, 1, 0) as the mote's velocity, scaled by RISE_SPEED.
            level.sendParticles(
                EKParticles.eideticMote(),
                user.x + cos(angle) * RING_RADIUS, user.y + RING_HEIGHT, user.z + sin(angle) * RING_RADIUS,
                0, 0.0, 1.0, 0.0, RISE_SPEED,
            )
        }
    }

    /** Carry [player] to the location of [page]; false when no library holds the page. */
    private fun travel(player: ServerPlayer, page: String): Boolean {
        // A shelf's page has its own location; written text goes to a page that starts with it.
        val location = BookshelfPage.locations(page).firstOrNull()
            ?: BookshelfPage.locationStartingWith(ScribesDeskNetwork.words(page), page.hashCode().toLong())
            ?: return false
        val level = player.server.getLevel(SselithRepertory.LEVEL_KEY) ?: return false
        val target = BlockPos(location.x, location.y, location.z)
        val spot = standingSpot(level, target) ?: target
        SselithDustBurst.at(player.serverLevel(), player)
        player.teleportTo(level, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, player.yRot, player.xRot)
        SselithDustBurst.at(level, player)
        player.fallDistance = 0f
        // Set after the teleport: entering Sselith picks a new parallel.
        SselithParallel.set(player, location.parallel)
        return true
    }

    /** The nearest feet position within [SEARCH_RADIUS] of [target] with ground below and room to stand. */
    private fun standingSpot(level: ServerLevel, target: BlockPos): BlockPos? {
        val feet = BlockPos.MutableBlockPos()
        for (offset in SEARCH_OFFSETS) {
            feet.setWithOffset(target, offset)
            if (feet.y - 1 < level.minBuildHeight || feet.y + 1 >= level.maxBuildHeight) continue
            if (isStandable(level, feet)) return feet.immutable()
        }
        return null
    }

    private fun isStandable(level: ServerLevel, feet: BlockPos): Boolean {
        val ground = feet.below()
        val head = feet.above()
        return level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP) &&
            level.getBlockState(feet).getCollisionShape(level, feet).isEmpty &&
            level.getBlockState(head).getCollisionShape(level, head).isEmpty &&
            level.getFluidState(feet).isEmpty && level.getFluidState(head).isEmpty
    }

    companion object {
        const val SAVED_KEY = "message.enderkinesis.eidetic_bookmark.saved"

        // Twice a bow's full draw.
        private const val CHARGE_TICKS = 40
        private const val TAP_TICKS = 5
        private const val COOLDOWN_TICKS = 20
        private const val USE_DURATION = 72_000
        private const val SEARCH_RADIUS = 5

        private const val RING_RADIUS = 0.9
        private const val RING_HEIGHT = 0.1
        private const val RING_SPIN = 0.35
        private const val RING_MIN_MOTES = 2
        private const val RING_GROWTH_TICKS = 14
        private const val RISE_SPEED = 0.1

        private const val TAG = "EideticPage"

        // Every offset in the search sphere, nearest first.
        private val SEARCH_OFFSETS: List<BlockPos> = buildList {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
                for (dz in -SEARCH_RADIUS..SEARCH_RADIUS) {
                    if (dx * dx + dy * dy + dz * dz <= SEARCH_RADIUS * SEARCH_RADIUS) add(BlockPos(dx, dy, dz))
                }
            }
        }.sortedBy { it.distSqr(BlockPos.ZERO) }

        fun saved(stack: ItemStack): String? = stack.tag?.getString(TAG)?.takeIf { it.isNotEmpty() }

        fun inscribed(page: String): ItemStack =
            ItemStack(EKItems.EIDETIC_BOOKMARK.get()).also { it.getOrCreateTag().putString(TAG, page) }
    }
}
