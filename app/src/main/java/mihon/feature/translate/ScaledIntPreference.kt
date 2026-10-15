package mihon.feature.translate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference

/**
 * [base] seen in units of [factor]: a slider over a character budget moves in
 * thousands while the stored value stays in characters, so existing settings
 * keep their meaning. Mihon's slider binds to a preference directly and has
 * no other way to scale what it shows.
 */
class ScaledIntPreference(
    private val base: Preference<Int>,
    private val factor: Int,
) : Preference<Int> {

    override fun key(): String = base.key()

    override fun get(): Int = base.get() / factor

    override fun set(value: Int) = base.set(value * factor)

    override fun isSet(): Boolean = base.isSet()

    override fun delete() = base.delete()

    override fun defaultValue(): Int = base.defaultValue() / factor

    override fun changes(): Flow<Int> = base.changes().map { it / factor }

    override fun stateIn(scope: CoroutineScope): StateFlow<Int> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())
}
