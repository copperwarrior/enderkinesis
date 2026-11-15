package org.shipwrights.enderkinesis.construction

import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import java.util.UUID

/** A stored plan file: the owner's folder, the SHA-256 of the uploaded file, and its display name and size. */
data class PlanRef(val owner: UUID, val hash: String, val name: String, val size: Vec3i) {
    init {
        require(isHash(hash)) { "bad plan hash: $hash" }
        require(name.length <= MAX_NAME) { "plan name too long: ${name.length}" }
    }

    // Two owners can store the same file, so a hash alone does not name one plan. Built once for per-frame lookups.
    val key: PlanKey = PlanKey(owner, hash)

    companion object {
        const val HASH_LENGTH = 64
        const val MAX_NAME = 64

        /** Lowercase hex SHA-256 only, so a hash is always a safe file name. */
        fun isHash(text: String): Boolean =
            text.length == HASH_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

data class PlanKey(val owner: UUID, val hash: String)

private const val OWNER = "Owner"
private const val HASH = "Hash"
private const val NAME = "Name"
private const val SIZE = "Size"

fun PlanRef.toTag(): CompoundTag = CompoundTag().also {
    it.putUUID(OWNER, owner)
    it.putString(HASH, hash)
    it.putString(NAME, name)
    it.putIntArray(SIZE, intArrayOf(size.x, size.y, size.z))
}

/** Null when the tag is not a whole, valid plan reference. */
fun CompoundTag.toPlanRef(): PlanRef? {
    if (!hasUUID(OWNER)) return null
    val hash = getString(HASH)
    val name = getString(NAME)
    val size = getIntArray(SIZE)
    if (!PlanRef.isHash(hash) || name.length > PlanRef.MAX_NAME || size.size != 3) return null
    return PlanRef(getUUID(OWNER), hash, name, Vec3i(size[0], size[1], size[2]))
}

fun FriendlyByteBuf.writePlanRef(ref: PlanRef) {
    writeUUID(ref.owner)
    writeUtf(ref.hash, PlanRef.HASH_LENGTH)
    writeUtf(ref.name, PlanRef.MAX_NAME)
    writeVarInt(ref.size.x)
    writeVarInt(ref.size.y)
    writeVarInt(ref.size.z)
}

/** Null when the hash or name is not valid; the reader still consumes the whole entry. */
fun FriendlyByteBuf.readPlanRef(): PlanRef? {
    val owner = readUUID()
    val hash = readUtf(PlanRef.HASH_LENGTH)
    val name = readUtf(PlanRef.MAX_NAME)
    val size = Vec3i(readVarInt(), readVarInt(), readVarInt())
    return if (PlanRef.isHash(hash)) PlanRef(owner, hash, name, size) else null
}
