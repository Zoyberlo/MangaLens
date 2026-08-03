package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The services behind the recognition and translation engines all want a key
 * fetched from a console the user has never opened. Each guide is the shortest
 * honest path to one, including the parts people trip over — Vision needing a
 * billing account even for its free tier, DeepL's `:fx` suffix, Azure's
 * separate endpoint.
 */
enum class ApiKeyGuide(
    val titleRes: StringResource,
    val stepsRes: StringResource,
    val url: String,
) {
    GOOGLE_VISION(
        MR.strings.pref_vision_api_key,
        MR.strings.guide_vision_steps,
        "https://console.cloud.google.com/apis/credentials",
    ),
    AZURE(
        MR.strings.pref_azure_api_key,
        MR.strings.guide_azure_steps,
        "https://portal.azure.com/#create/Microsoft.CognitiveServicesComputerVision",
    ),
    GEMINI(
        MR.strings.pref_gemini_api_key,
        MR.strings.guide_gemini_steps,
        "https://aistudio.google.com/apikey",
    ),
    DEEPL(
        MR.strings.pref_deepl_api_key,
        MR.strings.guide_deepl_steps,
        "https://www.deepl.com/pro-api",
    ),
}

@Composable
fun ApiKeyGuideDialog(guide: ApiKeyGuide, onDismissRequest: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(guide.titleRes)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(guide.stepsRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = guide.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    uriHandler.openUri(guide.url)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_open_in_browser))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_close))
            }
        },
    )
}
