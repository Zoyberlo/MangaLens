package mihon.feature.translate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The fork's local vocabulary: words saved from the translate overlay.
 * Each entry can be deleted or sent to the companion words-app.
 */
class SavedWordsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val store = remember { Injekt.get<VocabularyStore>() }
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        val words by store.words.collectAsState()
        val translatedBlocks by readerPreferences.translatedBlockCount.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_saved_words),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            if (words.isEmpty() && translatedBlocks == 0) {
                EmptyScreen(
                    stringRes = MR.strings.saved_words_empty,
                    modifier = Modifier.padding(paddingValues),
                )
                return@Scaffold
            }

            val weekAgo = remember { System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
            val savedThisWeek = words.count { it.savedAt >= weekAgo }

            LazyColumn(contentPadding = paddingValues) {
                item(key = "stats") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        StatCell(
                            value = words.size,
                            label = stringResource(MR.strings.stats_words_total),
                            modifier = Modifier.weight(1f),
                        )
                        StatCell(
                            value = savedThisWeek,
                            label = stringResource(MR.strings.stats_words_week),
                            modifier = Modifier.weight(1f),
                        )
                        StatCell(
                            value = translatedBlocks,
                            label = stringResource(MR.strings.stats_blocks_translated),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                items(words.asReversed(), key = { "${it.word}:${it.targetLang}:${it.savedAt}" }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.word,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (entry.translation.isNotBlank()) {
                                Text(
                                    text = entry.translation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                WordsAppBridge.saveWord(context, entry.word, entry.translation.ifBlank { null })
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Send,
                                contentDescription = stringResource(MR.strings.action_send_to_words_app),
                            )
                        }
                        IconButton(onClick = { store.remove(entry) }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(MR.strings.action_delete),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
