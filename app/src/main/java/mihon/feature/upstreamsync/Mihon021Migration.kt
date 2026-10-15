package mihon.feature.upstreamsync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import eu.kanade.domain.source.service.SourcePreferences
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.core.migration.migrations.ChapterNameSuffixMigration
import mihon.core.migration.migrations.ContentWarningMigration
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Runs Mihon's 0.20 → 0.21 migrations for MangaLens users.
 *
 * Mihon numbers its migrations by its own version codes — 30 and 34 for
 * 0.21 — and the migrator runs those between the previous and the current
 * code. MangaLens codes start at 100, so an upgrade from 1.0.4 to 1.0.5 never
 * crosses 30 or 34 and both would silently never run. One of them keeps the
 * folder names 0.20.x gave downloaded chapters; without it every existing
 * download reads as missing.
 *
 * Every MangaLens up to 1.0.4 was built on Mihon 0.20.x, so this hands
 * Mihon's own migrations the version they would have seen: 0.20.4's code.
 * The logic stays theirs.
 */
@ContributesIntoSet(AppScope::class)
class Mihon021Migration(
    private val libraryPreferences: LibraryPreferences,
    private val preferenceStore: PreferenceStore,
    private val sourcePreferences: SourcePreferences,
) : Migration {

    override val version: Float = 105f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        if (migrationContext.previousVersion !in MANGALENS_ON_MIHON_020) return true
        val asMihon = MigrationContext(migrationContext.dryrun, previousVersion = MIHON_0_20_4)
        val suffix = ChapterNameSuffixMigration(libraryPreferences).invoke(asMihon)
        val contentWarning = ContentWarningMigration(preferenceStore, sourcePreferences).invoke(asMihon)
        return suffix && contentWarning
    }

    private companion object {
        val MANGALENS_ON_MIHON_020 = 100..104
        const val MIHON_0_20_4 = 29
    }
}
