package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClawhodPickupTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun slots() = MutableList(HAND_SLOTS) { ItemStack.EMPTY }

    private fun named(stack: ItemStack): ItemStack = stack.also { it.setHoverName(Component.literal("x")) }

    @Test
    fun `empty slots give a full stack of room each`() {
        pickupRoom(slots(), ItemStack(Items.DIAMOND)) shouldBe HAND_SLOTS * 64
        pickupRoom(slots(), ItemStack(Items.ENDER_PEARL)) shouldBe HAND_SLOTS * 16
    }

    @Test
    fun `an insert tops up the same item first, then takes empty slots`() {
        val slots = slots()
        slots[1] = ItemStack(Items.FLINT, 60)
        pickupInsert(slots, ItemStack(Items.FLINT, 10), limit = 10) shouldBe 10
        slots[1].count shouldBe 64
        slots[0].count shouldBe 6
        slots[0].item shouldBe Items.FLINT
    }

    @Test
    fun `an insert stops at the limit and does not change the offered stack`() {
        val slots = slots()
        val offered = ItemStack(Items.STICK, 20)
        pickupInsert(slots, offered, limit = 5) shouldBe 5
        offered.count shouldBe 20
        slots.sumOf { it.count } shouldBe 5
    }

    @Test
    fun `items with other data do not stack together`() {
        val slots = MutableList(1) { ItemStack(Items.DIAMOND, 1) }
        pickupRoom(slots, named(ItemStack(Items.DIAMOND))) shouldBe 0
        pickupInsert(slots, named(ItemStack(Items.DIAMOND)), limit = 1) shouldBe 0
        pickupRoom(slots, ItemStack(Items.DIAMOND)) shouldBe 63
    }

    @Test
    fun `full slots take nothing`() {
        val slots = MutableList(HAND_SLOTS) { ItemStack(Items.DIRT, 64) }
        pickupRoom(slots, ItemStack(Items.DIAMOND)) shouldBe 0
        pickupInsert(slots, ItemStack(Items.DIAMOND, 4), limit = 4) shouldBe 0
    }

    @Test
    fun `a negative limit is refused`() {
        shouldThrow<IllegalArgumentException> { pickupInsert(slots(), ItemStack(Items.DIAMOND), limit = -1) }
    }
}
