package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@Inject
class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val preferences: BasePreferences,
    private val networkPreferences: NetworkPreferences,
) {

    /**
     * Collects the log and opens a share sheet with it, pre-addressed to
     * [DEVELOPER_EMAIL].
     *
     * An app cannot post the report by itself: sending mail needs a mail
     * server, and any credential shipped inside the APK is readable by anyone
     * who unzips it. So the user presses Send. That also means nothing leaves
     * the device the user has not seen, which is the right default for a log
     * that carries the device model and whatever the crash dragged in with it.
     */
    suspend fun dumpLogs(exception: Throwable? = null) = withContext(Dispatchers.IO) {
        try {
            val file = context.createFileInCacheDir("mangalens_crash_logs.txt")

            val debugInfo = getDebugInfo()
            file.appendText(debugInfo + "\n\n")
            getExtensionsInfo()?.let { file.appendText("$it\n\n") }
            exception?.let { file.appendText("$it\n\n") }

            val logPriority = if (networkPreferences.verboseLogging.get()) "V" else "E"
            Runtime.getRuntime().exec("logcat *:$logPriority -d -v year -v zone -f ${file.absolutePath}").waitFor()

            val uri = file.getUriCompat(context)
            val intent = uri.toShareIntent(
                context = context,
                type = "text/plain",
                // Repeated in the body because mail clients hide attachments
                // behind a tap, and this is the half that identifies the build
                message = debugInfo,
                recipient = DEVELOPER_EMAIL,
                // Sorts the inbox by build and device without opening anything
                subject = "MangaLens ${BuildConfig.VERSION_NAME} — ${Build.MANUFACTURER} ${Build.MODEL}",
            )
            withContext(Dispatchers.Main) { context.startActivity(intent) }
        } catch (_: Throwable) {
            withContext(Dispatchers.Main) { context.toast("Failed to get logs") }
        }
    }

    fun getDebugInfo(): String {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Installation ID: ${preferences.installationId.get()}
            Skipped app update: ${preferences.skippedAppUpdate.get().ifEmpty { "None" }}
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${now.toLocalDateTime(tz)}${tz.offsetAt(now)}
        """.trimIndent()
    }

    private suspend fun getExtensionsInfo(): String? {
        val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

        val outdatedInfoList = extensionManager.getLoadedExtensions()
            .sortedBy { it.name }
            .mapNotNull {
                val availableExtension = availableExtensions[it.pkgName]
                val hasUpdate = (availableExtension?.versionCode ?: 0) > it.versionCode

                if (!hasUpdate && !it.isObsolete) return@mapNotNull null

                """
                    - ${it.name}
                      Installed: ${it.versionName} / Available: ${availableExtension?.versionName ?: "?"}
                      Orphaned: ${it.isObsolete}
                """.trimIndent()
            }

        val notLoadedInfoList = extensionManager.getNotLoadedExtensions()
            .sortedBy { it.name }
            .map { extension ->
                buildString {
                    appendLine("- ${extension.name}")
                    appendLine("  Installed: ${extension.versionName} (lib ${extension.libVersion ?: "?"})")
                    append("  Not loaded: ${extension.reason.description}")

                    val reason = extension.reason
                    if (reason is Extension.NotLoaded.Reason.Failed) {
                        appendLine()
                        append(reason.stackTrace.trimEnd().prependIndent("  "))
                    }
                }
            }

        val extensionInfoList = outdatedInfoList + notLoadedInfoList

        return if (extensionInfoList.isNotEmpty()) {
            (listOf("Problematic extensions:") + extensionInfoList)
                .joinToString("\n")
        } else {
            null
        }
    }

    companion object {
        /** Where reports go. The fork has no issue tracker staffed by anyone else. */
        const val DEVELOPER_EMAIL = "zyberdebug@gmail.com"
    }
}

private val Extension.NotLoaded.Reason.description: String
    get() = when (this) {
        is Extension.NotLoaded.Reason.Untrusted -> "Untrusted"
        Extension.NotLoaded.Reason.Filtered -> "Filtered by content warning"
        Extension.NotLoaded.Reason.Unsigned -> "Unsigned"
        Extension.NotLoaded.Reason.UnsupportedLibVersion -> "Unsupported lib version"
        Extension.NotLoaded.Reason.Malformed -> "Malformed"
        is Extension.NotLoaded.Reason.Failed -> "Failed ($message)"
    }
