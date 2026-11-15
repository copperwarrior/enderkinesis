package org.shipwrights.enderkinesis.construction

import com.mojang.datafixers.DataFixer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Converts one gzip structure file into a section file at [target], the same way for an upload and a local sketch.
 * [trusted] keeps admin blocks. Throws IOException (also [PlanFormatException]) when the file cannot be read,
 * and CancellationException when [cancelled] turns true. Worker thread.
 */
fun convertPlanFile(source: Path, target: Path, scratch: Path, fixer: DataFixer, trusted: Boolean, cancelled: () -> Boolean): PlanCheck {
    val sanitizer = PlanPaletteSanitizer.vanilla(fixer, trusted)
    val result = PlanSectionWriter.convert(source, target, scratch, sanitizer, cancelled)
    if (result is PlanConversion.Refused) Files.deleteIfExists(target)
    return planCheckOf(result, sanitizer.issues)
}

fun isKnownPlanBlock(name: String): Boolean =
    ResourceLocation.tryParse(name)?.let { BuiltInRegistries.BLOCK.containsKey(it) } == true

/** `<root>/<owner>/<hash>.plan`. Only a checked hash and a UUID go into the path, so it cannot leave [root]. */
fun planFile(root: Path, owner: UUID, hash: String): Path {
    require(PlanRef.isHash(hash)) { "bad plan hash: $hash" }
    val base = root.normalize()
    val file = base.resolve(owner.toString()).resolve("$hash.plan").normalize()
    check(file.startsWith(base)) { "plan path left its folder: $file" }
    return file
}

/** A display name from a file name: no `.nbt`, no control or path characters, at most [PlanRef.MAX_NAME]. */
fun cleanPlanName(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".nbt")
    val clean = base.filter { it.isLetterOrDigit() || it in " _-.()" }.trim().take(PlanRef.MAX_NAME)
    return clean.ifEmpty { "plan" }
}
