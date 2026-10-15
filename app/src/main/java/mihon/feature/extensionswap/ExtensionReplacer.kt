package mihon.feature.extensionswap

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.core.metro.metroGraph
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Swaps extensions no repo can update for the same extension from a repo
 * that can (see [ExtensionReplacement]).
 *
 * The order is forced: Android refuses to install over a package signed with
 * another key, so the old one goes first, and between the two steps the
 * extension's sources are missing. Library entries survive that — they hold a
 * source id, not the extension — but a failed install would leave them
 * unreadable until the user installs by hand, so the new apk is checked to be
 * reachable before anything is removed.
 */
@Inject
@SingleIn(AppScope::class)
class ExtensionReplacer(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val getFavorites: GetFavorites,
    private val networkHelper: NetworkHelper,
    @AppCoroutineScope private val scope: CoroutineScope,
) {

    sealed interface Progress {
        data object Idle : Progress

        data class Working(val name: String, val index: Int, val total: Int, val step: Step) : Progress

        data class Finished(val replaced: List<String>, val failed: List<Pair<String, Failure>>) : Progress
    }

    enum class Step { CHECKING, UNINSTALLING, INSTALLING }

    enum class Failure {
        /** The new apk could not be reached; nothing was changed. */
        UNREACHABLE,

        /** The user declined the uninstall; nothing was changed. */
        KEPT,

        /** The old one is gone and the new one did not install. */
        INSTALL_FAILED,
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val candidates: Flow<List<ExtensionReplacement>> =
        combine(extensionManager.loadedExtensionsFlow, extensionManager.availableExtensionsFlow) { loaded, available ->
            loaded to available
        }.mapLatest { (loaded, available) ->
            val librarySources = getFavorites.await().groupingBy { it.source }.eachCount()
            findReplacements(loaded, available, librarySources)
        }

    fun replace(items: List<ExtensionReplacement>) {
        if (items.isEmpty() || job?.isActive == true) return
        job = scope.launch {
            val replaced = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, Failure>>()
            items.forEachIndexed { index, item ->
                val name = item.installed.name
                val failure = replaceOne(item) { step ->
                    _progress.value =
                        Progress.Working(name, index + 1, items.size, step)
                }
                if (failure == null) replaced += name else failed += name to failure
                logcat(LogPriority.INFO) {
                    "Extension swap: $name -> ${item.replacement.store.name}: ${failure ?: "done"}"
                }
            }
            _progress.value = Progress.Finished(replaced, failed)
        }
    }

    fun cancel() {
        job?.cancel()
        _progress.value = Progress.Idle
    }

    fun dismiss() {
        if (job?.isActive != true) _progress.value = Progress.Idle
    }

    private suspend fun replaceOne(item: ExtensionReplacement, onStep: (Step) -> Unit): Failure? {
        onStep(Step.CHECKING)
        if (!isReachable(item.replacement.apkUrl)) return Failure.UNREACHABLE

        onStep(Step.UNINSTALLING)
        val pkgName = item.installed.pkgName
        extensionManager.uninstallExtension(item.installed)
        // The system dialog gives no answer back; the package disappearing is the answer
        val removed = withTimeoutOrNull(UNINSTALL_WAIT) {
            while (context.isPackageInstalled(pkgName)) delay(POLL)
            true
        } ?: false
        if (!removed) return Failure.KEPT

        onStep(Step.INSTALLING)
        val result = extensionManager.installExtension(item.replacement).first { it.isCompleted() }
        return if (result == InstallStep.Installed) null else Failure.INSTALL_FAILED
    }

    private suspend fun isReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).head().build()
            networkHelper.client.newCall(request).await().use { it.isSuccessful }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Extension swap: $url unreachable" }
            false
        }
    }

    private companion object {
        /** Long enough to read the dialog; a declined uninstall otherwise reads as "still deciding". */
        val UNINSTALL_WAIT = 2.minutes
        val POLL = 500.milliseconds
    }
}

@ContributesTo(AppScope::class)
interface ExtensionSwapGraph {
    val extensionReplacer: ExtensionReplacer
}

val Context.extensionReplacer: ExtensionReplacer
    get() = metroGraph<ExtensionSwapGraph>().extensionReplacer
