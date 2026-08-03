package mihon.feature.migratefromapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class DetectedApp(val packageName: String, val label: String)

private data class FoundBackup(val name: String, val uri: String, val lastModified: Long)

/** A backup with the owning app resolved for display. */
private data class BackupEntry(
    val backup: FoundBackup,
    val appLabel: String,
    val icon: ImageBitmap?,
    val isOwnBackup: Boolean,
)

/**
 * Names of apps that produce Mihon-format backups, for prefixes whose app is
 * not installed (or never was, e.g. Tachiyomi). Keyed by the file-name prefix,
 * which is the package name — except legacy Tachiyomi, which used a plain name.
 */
private val KNOWN_BACKUP_SOURCES = mapOf(
    "app.mihon" to "Mihon",
    "app.mihon.debug" to "Mihon (debug)",
    "app.mihon.preview" to "Mihon Preview",
    "app.mihon.foss" to "Mihon FOSS",
    "tachiyomi" to "Tachiyomi",
    "eu.kanade.tachiyomi" to "Tachiyomi",
    "eu.kanade.tachiyomi.debug" to "Tachiyomi (debug)",
    "eu.kanade.tachiyomi.j2k" to "Tachiyomi J2K",
    "xyz.jmir.tachiyomi.mi" to "TachiyomiSY",
    "xyz.jmir.tachiyomi.mi.sy" to "TachiyomiSY",
    "komikku.app" to "Komikku",
)

/**
 * Backup file names are `<prefix>_<date>.tachibk`, where the prefix is the
 * producing app's package (legacy Tachiyomi used its plain name). Resolve it to
 * a real app name and icon so the list reads like apps, not file names.
 */
private fun FoundBackup.toEntry(context: Context, ownPackage: String): BackupEntry {
    val prefix = name.substringBefore('_').takeIf { it.isNotBlank() && it != name }
    var label: String? = KNOWN_BACKUP_SOURCES[prefix]
    var icon: ImageBitmap? = null
    if (prefix != null && prefix.contains('.')) {
        try {
            val info = context.packageManager.getApplicationInfo(prefix, 0)
            label = context.packageManager.getApplicationLabel(info).toString()
            icon = context.packageManager.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            // Not installed: fall back to the known-source name, or the prefix
        }
    }
    return BackupEntry(
        backup = this,
        appLabel = label ?: prefix ?: name,
        icon = icon,
        // Exact match only: app.mihon.tl.dev and app.mihon are different installs
        isOwnBackup = prefix == ownPackage,
    )
}

/**
 * Migration helper: detects installed Mihon-family apps and finds their
 * .tachibk backup files in a user-picked folder, handing the chosen file to
 * the standard restore flow. Reading another app's database directly is
 * impossible (sandboxing) — backups are the only migration path.
 */
class MigrateFromAppScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val detectedApps = remember {
            KNOWN_APPS.mapNotNull { pkg ->
                try {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    DetectedApp(pkg, context.packageManager.getApplicationLabel(info).toString())
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }

        var backups by remember { mutableStateOf<List<FoundBackup>>(emptyList()) }
        var scanned by remember { mutableStateOf(false) }
        var scanning by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val entries = remember(backups) { backups.map { it.toEntry(context, context.packageName) } }
        val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // The one-shot grant is still enough to read during this session
            }
            val root = UniFile.fromUri(context, uri) ?: return@rememberLauncherForActivityResult
            val found = mutableListOf<FoundBackup>()
            scanForBackups(root, depth = 0, found = found)
            backups = found.sortedByDescending { it.lastModified }.take(20)
            scanned = true
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_migrate_from_app),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            LazyColumn(contentPadding = paddingValues) {
                item {
                    Text(
                        text = stringResource(MR.strings.migrate_from_app_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(detectedApps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ?.let(context::startActivity)
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                            )
                        }
                    }
                }
                item {
                    Button(
                        enabled = !scanning,
                        onClick = {
                            if (hasAllFilesAccess()) {
                                scanning = true
                                scope.launch {
                                    backups = withIOContext { scanSharedStorage() }
                                    scanning = false
                                    scanned = true
                                }
                            } else {
                                requestAllFilesAccess(context)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    scanning -> MR.strings.migrate_from_app_scanning
                                    hasAllFilesAccess() -> MR.strings.migrate_from_app_scan_device
                                    else -> MR.strings.migrate_from_app_scan_needs_permission
                                },
                            ),
                        )
                    }
                }
                item {
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(MR.strings.migrate_from_app_find_backups))
                    }
                }
                if (scanned && backups.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.migrate_from_app_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(entries, key = { it.backup.uri }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (entry.icon != null) {
                            Image(
                                bitmap = entry.icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(40.dp),
                            )
                        } else {
                            // Uninstalled or unrecognized producer
                            Icon(
                                imageVector = Icons.Outlined.Archive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(40.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (entry.isOwnBackup) {
                                    stringResource(MR.strings.migrate_from_app_own_backup, entry.appLabel)
                                } else {
                                    entry.appLabel
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = dateFormat.format(Date(entry.backup.lastModified)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { navigator.push(MigrateRestoreScreen(entry.backup.uri)) }) {
                            Icon(imageVector = Icons.Outlined.Restore, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    /**
     * All-files access lets the screen search storage on its own instead of
     * making the user find the backup folder. Optional: the folder picker
     * remains available without any permission.
     */
    private fun hasAllFilesAccess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            false
        }
    }

    private fun requestAllFilesAccess(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Walks shared storage for backup files (needs all-files access). */
    private fun scanSharedStorage(): List<FoundBackup> {
        val found = mutableListOf<FoundBackup>()
        val root = android.os.Environment.getExternalStorageDirectory() ?: return emptyList()
        scanDirectory(root, depth = 0, found = found)
        return found.sortedByDescending { it.lastModified }.take(20)
    }

    private fun scanDirectory(dir: File, depth: Int, found: MutableList<FoundBackup>) {
        if (depth > MAX_SCAN_DEPTH || found.size >= MAX_SCAN_RESULTS) return
        // Android/ holds per-app sandboxes; nothing user-visible lives there
        if (dir.name == "Android" || dir.name.startsWith(".")) return
        dir.listFiles()?.forEach { file ->
            if (found.size >= MAX_SCAN_RESULTS) return
            when {
                file.isDirectory -> scanDirectory(file, depth + 1, found)
                file.name.endsWith(".tachibk") || file.name.endsWith(".proto.gz") ->
                    found += FoundBackup(file.name, Uri.fromFile(file).toString(), file.lastModified())
            }
        }
    }

    private fun scanForBackups(dir: UniFile, depth: Int, found: MutableList<FoundBackup>) {
        if (depth > MAX_SCAN_DEPTH || found.size >= MAX_SCAN_RESULTS) return
        dir.listFiles()?.forEach { file ->
            if (found.size >= MAX_SCAN_RESULTS) return
            if (file.isDirectory) {
                scanForBackups(file, depth + 1, found)
            } else if (file.name?.endsWith(".tachibk") == true || file.name?.endsWith(".proto.gz") == true) {
                found += FoundBackup(file.name!!, file.uri.toString(), file.lastModified())
            }
        }
    }

    private companion object {
        const val MAX_SCAN_DEPTH = 3
        const val MAX_SCAN_RESULTS = 50

        val KNOWN_APPS = listOf(
            "app.mihon",
            "app.mihon.debug",
            "app.mihon.tl.dev",
            "eu.kanade.tachiyomi",
            "xyz.jmir.tachiyomi.mi.sy",
            "eu.kanade.tachiyomi.j2k",
            "komikku.app",
        )
    }
}
