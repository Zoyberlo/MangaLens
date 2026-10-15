package mihon.feature.translate

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import mihon.core.metro.metroGraph

/**
 * The translation feature's entry points into Mihon's dependency graph.
 *
 * Contributed rather than listed in `AppGraph`: Metro merges this interface
 * into the app graph at compile time, so the feature plugs in without a line
 * in a file upstream maintains. The classes themselves are constructor-injected
 * singletons; this only exposes the ones code outside the graph — the reader,
 * its page holders, settings screens — needs to reach.
 */
@ContributesTo(AppScope::class)
interface TranslationGraph {
    val translationPreferences: TranslationPreferences
    val textTranslator: TextTranslator
    val pageTranslator: PageTranslator
    val quotaNotifier: QuotaNotifier
    val cloudTextRecognizer: CloudTextRecognizer
    val paddleTextRecognizer: PaddleTextRecognizer
}

val Context.translationGraph: TranslationGraph
    get() = metroGraph()
