package mihon.feature.migratefromapp

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import mihon.core.metro.metroGraph
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId

/** What the migrate-from-app screens need from Mihon's graph; see `TranslationGraph`. */
@ContributesTo(AppScope::class)
interface MigrateFromAppGraph {
    val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId
}

val Context.migrateFromAppGraph: MigrateFromAppGraph
    get() = metroGraph()
