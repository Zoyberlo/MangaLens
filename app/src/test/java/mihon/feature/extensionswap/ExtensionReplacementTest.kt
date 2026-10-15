package mihon.feature.extensionswap

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

/**
 * The case that started this: Asura Scans installed from the yuzono mirror
 * (its own key), the mirror gone, Keiyoushi listing the same package with the
 * same source ids under a different key.
 */
class ExtensionReplacementTest {

    private val yuzonoKey = "cbec121a"
    private val keiyoushiKey = "9add655a"
    private val keiyoushi = store("Keiyoushi", keiyoushiKey)

    private val asuraId = 6247824327199706550L

    @Test
    fun `offers the other repo's listing for an orphaned extension`() {
        val installed = loaded("en.asurascans", yuzonoKey, asuraId)
        val listing = available("en.asurascans", keiyoushi, 69, asuraId)

        val result = findReplacements(listOf(installed), listOf(listing), mapOf(asuraId to 10))

        result shouldHaveSize 1
        result[0].replacement shouldBe listing
        result[0].inLibrary shouldBe 10
    }

    @Test
    fun `leaves alone an extension a configured repo can update`() {
        val installed = loaded("en.asurascans", keiyoushiKey, asuraId)
        val listing = available("en.asurascans", keiyoushi, 69, asuraId)

        findReplacements(listOf(installed), listOf(listing), mapOf(asuraId to 10)).shouldBeEmpty()
    }

    @Test
    fun `refuses a listing that drops a source the library reads from`() {
        val installed = loaded("all.mangadex", yuzonoKey, 1L, 2L)
        val listing = available("all.mangadex", keiyoushi, 106000, 1L)

        findReplacements(listOf(installed), listOf(listing), mapOf(2L to 1)).shouldBeEmpty()
    }

    @Test
    fun `a dropped source nobody reads from does not block the swap`() {
        val installed = loaded("all.mangadex", yuzonoKey, 1L, 2L)
        val listing = available("all.mangadex", keiyoushi, 106000, 1L)

        val result = findReplacements(listOf(installed), listOf(listing), mapOf(1L to 3))

        result shouldHaveSize 1
        result[0].inLibrary shouldBe 3
    }

    @Test
    fun `takes the newest of several listings`() {
        val installed = loaded("en.asurascans", yuzonoKey, asuraId)
        val older = available("en.asurascans", store("Mirror", "aaaa"), 60, asuraId)
        val newer = available("en.asurascans", keiyoushi, 69, asuraId)

        findReplacements(listOf(installed), listOf(older, newer), emptyMap())[0].replacement shouldBe newer
    }

    @Test
    fun `nothing to offer when no repo lists the package`() {
        val installed = loaded("en.asurascans", yuzonoKey, asuraId)
        val other = available("en.atsumaru", keiyoushi, 25, 2327480808438768017L)

        findReplacements(listOf(installed), listOf(other), emptyMap()).shouldBeEmpty()
    }

    private fun store(name: String, key: String) = ExtensionStore(
        indexUrl = "https://example.org/$name/index.pb",
        name = name,
        badgeLabel = name.take(3),
        signingKey = key,
        contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun loaded(pkg: String, key: String, vararg sourceIds: Long) = Extension.Loaded(
        name = pkg,
        pkgName = "eu.kanade.tachiyomi.extension.$pkg",
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        signatures = listOf(key),
        pkgFactory = null,
        sources = sourceIds.map { id -> mockk<Source> { every { this@mockk.id } returns id } },
    )

    private fun available(pkg: String, store: ExtensionStore, code: Long, vararg sourceIds: Long) =
        Extension.Available(
            name = pkg,
            pkgName = "eu.kanade.tachiyomi.extension.$pkg",
            versionName = "1.6.$code",
            versionCode = code,
            libVersion = 1.6,
            lang = "en",
            contentWarning = ContentWarning.SAFE,
            sources = sourceIds.map { Extension.Available.Source(it, "en", pkg, "https://example.org") },
            apkUrl = "https://example.org/$pkg.apk",
            iconUrl = "https://example.org/$pkg.png",
            store = store,
        )
}
