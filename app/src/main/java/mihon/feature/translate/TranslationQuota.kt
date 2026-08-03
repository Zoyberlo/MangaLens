package mihon.feature.translate

import kotlinx.coroutines.flow.MutableSharedFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.getAndSet
import java.util.Calendar

/** Paid services whose usage is metered on the user's own account. */
enum class QuotaKind {
    CLOUD_OCR,
    AZURE_OCR,
    GEMINI_OCR,
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
 * screen. Every metered service reports here so the reader only has to listen
 * in one place.
 */
class QuotaNotifier {
    val events = MutableSharedFlow<QuotaEvent>(extraBufferCapacity = 8)

    fun report(kind: QuotaKind, level: QuotaLevel) {
        events.tryEmit(QuotaEvent(kind, level))
    }
}

/**
 * One metered service's monthly budget. Every paid backend gets one of these
 * instead of repeating the same rollover-and-warn logic: the counter resets
 * lazily when the calendar month changes, spending is only recorded for calls
 * that actually succeeded, and a limit of zero means "no limit".
 *
 * [cost] is whatever the service bills — requests for the OCR engines,
 * characters for DeepL.
 */
class QuotaTracker(
    private val kind: QuotaKind,
    private val limitPref: Preference<Int>,
    private val usagePref: Preference<Int>,
    private val periodPref: Preference<String>,
    private val notifier: QuotaNotifier,
) {

    fun limit(): Int = limitPref.get()

    fun used(): Int {
        rollover()
        return usagePref.get()
    }

    /** False when [cost] would take the user past their own limit. */
    fun canSpend(cost: Int): Boolean {
        rollover()
        val limit = limitPref.get()
        if (limit <= 0) return true
        if (usagePref.get() + cost > limit) {
            notifier.report(kind, QuotaLevel.REACHED)
            return false
        }
        return true
    }

    fun record(cost: Int) {
        rollover()
        usagePref.getAndSet { it + cost }
        val limit = limitPref.get()
        if (limit <= 0) return
        val spent = usagePref.get()
        when {
            spent >= limit -> notifier.report(kind, QuotaLevel.REACHED)
            spent >= (limit * QUOTA_APPROACHING_RATIO).toInt() ->
                notifier.report(kind, QuotaLevel.APPROACHING)
        }
    }

    fun reportFailure() = notifier.report(kind, QuotaLevel.FAILED)

    private fun rollover() {
        val period = currentQuotaPeriod()
        if (periodPref.get() != period) {
            periodPref.set(period)
            usagePref.set(0)
        }
    }
}

/** Current calendar month, the period the free tiers reset on. */
internal fun currentQuotaPeriod(): String {
    val calendar = Calendar.getInstance()
    return "%04d-%02d".format(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
}

internal const val QUOTA_APPROACHING_RATIO = 0.9f
