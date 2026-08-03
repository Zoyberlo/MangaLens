package mihon.feature.migratefromapp

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private data class BackupPreview(
    val totalEntries: Int,
    val alreadyInLibrary: Int,
    val categories: Int,
    val hasSettings: Boolean,
)

/**
 * Shows what a backup would bring in before restoring it, and lets the user
 * pick how conflicts are handled. "Only new entries" filters the backup down
 * to series missing from the library and restores that copy, so nothing
 * already in the library is touched.
 */
class MigrateRestoreScreen(private val uriString: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var backup by remember { mutableStateOf<Backup?>(null) }
        var preview by remember { mutableStateOf<BackupPreview?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        var onlyNew by remember { mutableStateOf(true) }
        var restoreCategories by remember { mutableStateOf(true) }
        var restoreAppSettings by remember { mutableStateOf(false) }
        var restoreSourceSettings by remember { mutableStateOf(true) }
        var starting by remember { mutableStateOf(false) }

        LaunchedEffect(uriString) {
            try {
                val (decoded, computed) = withIOContext { decodeAndPreview(context, Uri.parse(uriString)) }
                backup = decoded
                preview = computed
            } catch (e: Exception) {
                error = e.message ?: "Failed to read backup"
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.migrate_restore_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val current = preview
            when {
                error != null -> Text(
                    text = error!!,
                    modifier = Modifier.padding(paddingValues).padding(16.dp),
                )
                current == null -> LoadingScreen(Modifier.padding(paddingValues))
                else -> Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(
                            MR.strings.migrate_restore_summary,
                            current.totalEntries,
                            current.totalEntries - current.alreadyInLibrary,
                            current.alreadyInLibrary,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )

                    SettingsChipRow(MR.strings.migrate_restore_conflict) {
                        FilterChip(
                            selected = onlyNew,
                            onClick = { onlyNew = true },
                            label = { Text(stringResource(MR.strings.migrate_restore_only_new)) },
                        )
                        FilterChip(
                            selected = !onlyNew,
                            onClick = { onlyNew = false },
                            label = { Text(stringResource(MR.strings.migrate_restore_add_and_update)) },
                        )
                    }

                    CheckboxItem(
                        label = stringResource(MR.strings.categories),
                        checked = restoreCategories,
                        onClick = { restoreCategories = !restoreCategories },
                    )
                    CheckboxItem(
                        label = stringResource(MR.strings.app_settings),
                        checked = restoreAppSettings,
                        onClick = { restoreAppSettings = !restoreAppSettings },
                    )
                    CheckboxItem(
                        label = stringResource(MR.strings.source_settings),
                        checked = restoreSourceSettings,
                        onClick = { restoreSourceSettings = !restoreSourceSettings },
                    )

                    Button(
                        enabled = !starting,
                        onClick = {
                            starting = true
                            scope.launch {
                                val source = backup ?: return@launch
                                val restoreUri = if (onlyNew) {
                                    withIOContext { writeFilteredBackup(context, source) }
                                } else {
                                    Uri.parse(uriString)
                                }
                                BackupRestoreJob.start(
                                    context = context,
                                    uri = restoreUri,
                                    options = RestoreOptions(
                                        libraryEntries = true,
                                        categories = restoreCategories,
                                        appSettings = restoreAppSettings,
                                        extensionStores = restoreSourceSettings,
                                        sourceSettings = restoreSourceSettings,
                                    ),
                                )
                                context.toast(MR.strings.restoring_backup)
                                navigator.pop()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(stringResource(MR.strings.action_start))
                    }
                }
            }
        }
    }

    private suspend fun decodeAndPreview(context: Context, uri: Uri): Pair<Backup, BackupPreview> {
        val backup = BackupDecoder(context).decode(uri)
        val getMangaByUrlAndSourceId = Injekt.get<GetMangaByUrlAndSourceId>()
        val existing = backup.backupManga.count { getMangaByUrlAndSourceId.await(it.url, it.source) != null }
        return backup to BackupPreview(
            totalEntries = backup.backupManga.size,
            alreadyInLibrary = existing,
            categories = backup.backupCategories.size,
            hasSettings = backup.backupPreferences.isNotEmpty() || backup.backupSourcePreferences.isNotEmpty(),
        )
    }

    /**
     * Writes a copy of [backup] without series that already exist locally, so
     * restoring it cannot modify anything currently in the library.
     */
    private suspend fun writeFilteredBackup(context: Context, backup: Backup): Uri {
        val getMangaByUrlAndSourceId = Injekt.get<GetMangaByUrlAndSourceId>()
        val newEntries = backup.backupManga.filter { getMangaByUrlAndSourceId.await(it.url, it.source) == null }
        val filtered = backup.copy(backupManga = newEntries)

        val bytes = Injekt.get<ProtoBuf>().encodeToByteArray(Backup.serializer(), filtered)
        val file = File(context.cacheDir, "migrate_filtered.tachibk")
        file.sink().gzip().buffer().use { it.write(bytes) }
        return Uri.fromFile(file)
    }
}
