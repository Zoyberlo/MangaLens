package mihon.feature.translate

import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Calendar

/** Paid services whose usage is metered on the user's own account. */
enum class QuotaKind {
    CLOUD_OCR,
    DEEPL,
}

enum class QuotaLevel {
    APPROACHING,
    REACHED,
    FAILED,
}

data class QuotaEvent(val kind: QuotaKind, val level: QuotaLevel)

/**
 * Carries quota news from the translation services to whatever UI is on
 * screen. Both metered services report here so the reader only has to listen
 * in one place.
 */
class QuotaNotifier {
    val events = MutableSharedFlow<QuotaEvent>(extraBufferCapacity = 8)

    fun report(kind: QuotaKind, level: QuotaLevel) {
        events.tryEmit(QuotaEvent(kind, level))
    }
}

/** Current calendar month, the period both free tiers reset on. */
internal fun currentQuotaPeriod(): String {
    val calendar = Calendar.getInstance()
    return "%04d-%02d".format(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
}

internal const val QUOTA_APPROACHING_RATIO = 0.9f
