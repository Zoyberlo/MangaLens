package mihon.feature.devlog

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Settings → Advanced entries for the persistent log: share it, clear it.
 * Empty when the log is not running, which is every build but debug.
 */
@Composable
fun devLogPreferences(): Array<Preference> {
    if (!PersistentLog.isInstalled) return emptyArray()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(-1L) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bytes = withIOContext { PersistentLog.totalBytes(context) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(MR.strings.devlog_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launchIO {
                            PersistentLog.clear(context)
                            bytes = PersistentLog.totalBytes(context)
                        }
                    },
                ) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    val size = if (bytes < 0) "…" else Formatter.formatShortFileSize(context, bytes)
    return arrayOf(
        Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.devlog_share),
            subtitle = stringResource(MR.strings.devlog_share_summary, size),
            onClick = { scope.launchIO { shareDevLogs(context) } },
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.devlog_clear),
            onClick = { confirmClear = true },
        ),
    )
}

/**
 * Zips every day's log, plus what logcat still holds, and opens a share sheet
 * addressed like a crash report. Logcat is the part this log cannot see on its
 * own: the native libraries (ML Kit, ONNX Runtime) and OkHttp write straight
 * to it.
 */
private suspend fun shareDevLogs(context: Context) {
    try {
        PersistentLog.sync()
        val logs = PersistentLog.files(context)
        val crashLogUtil = CrashLogUtil(context)
        val debugInfo = crashLogUtil.getDebugInfo()

        val recent = File(context.externalCacheDir, "logcat-recent.txt").apply { delete() }
        runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("logcat", "-d", "-v", "year", "-v", "zone", "-f", recent.absolutePath))
                .waitFor()
        }

        val zip = context.createFileInCacheDir("mangalens-dev-logs.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("device.txt"))
            out.write(debugInfo.toByteArray())
            out.closeEntry()
            for (file in logs + recent) {
                if (!file.exists()) continue
                out.putNextEntry(ZipEntry(file.name))
                if (file == recent) {
                    // The system's own lines never passed through the redactor
                    out.write(Redactor.redact(file.readText()).toByteArray())
                } else {
                    file.inputStream().use { it.copyTo(out) }
                }
                out.closeEntry()
            }
        }
        recent.delete()

        val uri = zip.getUriCompat(context)
        withUIContext {
            context.startActivity(
                uri.toShareIntent(
                    context = context,
                    type = "application/zip",
                    message = debugInfo,
                    recipient = CrashLogUtil.DEVELOPER_EMAIL,
                    subject = "MangaLens ${BuildConfig.VERSION_NAME} dev logs — ${Build.MANUFACTURER} ${Build.MODEL}",
                ),
            )
        }
    } catch (_: Throwable) {
        withUIContext { context.toast(MR.strings.devlog_share_failed) }
    }
}
