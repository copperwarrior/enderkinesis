package org.shipwrights.enderkinesis.construction

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component

/** One report line: a translation key and its plain-text arguments, so the server can send it as data. */
data class PlanIssue(val key: String, val args: List<String> = emptyList()) {
    fun toComponent(): Component = Component.translatable(key, *args.toTypedArray())

    companion object {
        const val MAX_KEY = 96
        const val MAX_ARG = 128
        const val MAX_ARGS = 4
    }
}

/** The server's answer to one upload. [ref] is the stored plan, or null when the server refused it. */
data class PlanUploadResult(val ref: PlanRef?, val issues: List<PlanIssue>) {
    val accepted: Boolean get() = ref != null

    companion object {
        const val MAX_ISSUES = 16
    }
}

fun FriendlyByteBuf.writePlanIssue(issue: PlanIssue) {
    writeUtf(issue.key, PlanIssue.MAX_KEY)
    val args = issue.args.take(PlanIssue.MAX_ARGS)
    writeVarInt(args.size)
    for (arg in args) writeUtf(arg.take(PlanIssue.MAX_ARG), PlanIssue.MAX_ARG)
}

fun FriendlyByteBuf.readPlanIssue(): PlanIssue {
    val key = readUtf(PlanIssue.MAX_KEY)
    val count = readVarInt()
    require(count in 0..PlanIssue.MAX_ARGS) { "bad plan issue argument count: $count" }
    return PlanIssue(key, List(count) { readUtf(PlanIssue.MAX_ARG) })
}

fun FriendlyByteBuf.writePlanUploadResult(result: PlanUploadResult) {
    writeBoolean(result.ref != null)
    result.ref?.let { writePlanRef(it) }
    val issues = result.issues.take(PlanUploadResult.MAX_ISSUES)
    writeVarInt(issues.size)
    for (issue in issues) writePlanIssue(issue)
}

fun FriendlyByteBuf.readPlanUploadResult(): PlanUploadResult {
    val ref = if (readBoolean()) readPlanRef() else null
    val count = readVarInt()
    require(count in 0..PlanUploadResult.MAX_ISSUES) { "bad plan issue count: $count" }
    return PlanUploadResult(ref, List(count) { readPlanIssue() })
}
