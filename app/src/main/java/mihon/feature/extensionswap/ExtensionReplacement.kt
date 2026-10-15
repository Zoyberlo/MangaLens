package mihon.feature.extensionswap

import eu.kanade.tachiyomi.extension.model.Extension

/**
 * An installed extension no repo of the user's can update, and the listing
 * from another repo that can take its place.
 *
 * Android installs an update only when it is signed with the same key, so an
 * extension that came from a repo the user no longer has — a dead mirror, an
 * app they migrated from — can never update from the repos they do have. It
 * has to be uninstalled and installed again from one of them.
 *
 * @param inLibrary how many library entries read from this extension's
 * sources: they keep working only because [replacement] has the same source
 * ids, which [findReplacements] checks.
 */
data class ExtensionReplacement(
    val installed: Extension.Loaded,
    val replacement: Extension.Available,
    val inLibrary: Int,
)

/**
 * Pairs every installed extension that no configured repo can update with a
 * listing that can replace it.
 *
 * Only loaded extensions are considered: the library is linked to sources by
 * id, and only a loaded extension says which sources it provides. A listing
 * qualifies when it is for the same package, comes from a repo with a key the
 * installed copy is not signed with, and offers every source the library
 * reads from — otherwise the swap would cut entries off from their source.
 *
 * @param librarySources how many library entries each source id holds.
 */
fun findReplacements(
    installed: List<Extension.Loaded>,
    available: List<Extension.Available>,
    librarySources: Map<Long, Int>,
): List<ExtensionReplacement> = installed.mapNotNull { extension ->
    // A repo with a matching key exists: it updates the extension normally
    if (extension.findListing(available) != null) return@mapNotNull null

    val usedSourceIds = extension.sources.map { it.id }.filter { (librarySources[it] ?: 0) > 0 }.toSet()
    val replacement = available
        .filter { it.pkgName == extension.pkgName && it.store.signingKey !in extension.signatures }
        .filter { listing -> listing.sources.map { it.id }.containsAll(usedSourceIds) }
        .maxWithOrNull(compareBy<Extension.Available> { it.versionCode }.thenBy { it.libVersion })
        ?: return@mapNotNull null

    ExtensionReplacement(
        installed = extension,
        replacement = replacement,
        inLibrary = usedSourceIds.sumOf { librarySources[it] ?: 0 },
    )
}
