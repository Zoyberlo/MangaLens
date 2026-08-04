package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.OffsetDateTime
import java.time.ZoneId

class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
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
    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val file = context.createFileInCacheDir("mangalens_crash_logs.txt")

            val debugInfo = getDebugInfo()
            file.appendText(debugInfo + "\n\n")
            getExtensionsInfo()?.let { file.appendText("$it\n\n") }
            exception?.let { file.appendText("$it\n\n") }

            Runtime.getRuntime().exec("logcat *:E -d -v year -v zone -f ${file.absolutePath}").waitFor()

            val uri = file.getUriCompat(context)
            context.startActivity(
                uri.toShareIntent(
                    context = context,
                    type = "text/plain",
                    // Repeated in the body because mail clients hide attachments
                    // behind a tap, and this is the half that identifies the build
                    message = debugInfo,
                    recipient = DEVELOPER_EMAIL,
                    // Sorts the inbox by build and device without opening anything
                    subject = "MangaLens ${BuildConfig.VERSION_NAME} — ${Build.MANUFACTURER} ${Build.MODEL}",
                ),
            )
        } catch (e: Throwable) {
            withUIContext { context.toast("Failed to get logs") }
        }
    }

    fun getDebugInfo(): String {
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Installation ID: ${preferences.installationId.get()}
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${OffsetDateTime.now(ZoneId.systemDefault())}
        """.trimIndent()
    }

    private fun getExtensionsInfo(): String? {
        val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

        val extensionInfoList = extensionManager.installedExtensionsFlow.value
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
