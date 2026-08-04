package tachiyomi.data.release

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the contract between the release workflow's file names and the
 * updater's asset picker.
 *
 * These two live far apart — one in `.github/workflows/release-fork.yml`, one
 * here — and nothing else connects them. Rename an APK and every installed copy
 * of the app quietly stops updating, or starts pulling the wrong architecture:
 * a failure that only shows up on someone else's phone, one release too late.
 */
class ReleaseAssetSelectionTest {

    /** Exactly what the release workflow uploads for a tag. */
    private val release = assets(
        "mangalens-v1.0.3-arm64-v8a.apk",
        "mangalens-v1.0.3-armeabi-v7a.apk",
        "mangalens-v1.0.3-universal.apk",
    )

    private fun assets(vararg names: String) = names.map { GitHubAsset(it, "https://dl/$it") }

    private fun pick(assets: List<GitHubAsset>, abi: String, isFoss: Boolean = false) =
        ReleaseServiceImpl.downloadLinkFor(assets, abi, isFoss)?.substringAfterLast('/')

    @Test
    fun `each architecture gets its own build`() {
        pick(release, "arm64-v8a") shouldBe "mangalens-v1.0.3-arm64-v8a.apk"
        pick(release, "armeabi-v7a") shouldBe "mangalens-v1.0.3-armeabi-v7a.apk"
    }

    @Test
    fun `an unbuilt architecture falls back to the universal build`() {
        // No x86 split ships, and an emulator must still be able to update
        pick(release, "x86_64") shouldBe "mangalens-v1.0.3-universal.apk"
    }

    @Test
    fun `a device reporting no ABI at all still gets something installable`() {
        pick(release, "") shouldBe "mangalens-v1.0.3-universal.apk"
    }

    @Test
    fun `a release built without splits still updates everyone`() {
        // What tagging without -Psplit-abis produces. It must degrade to the
        // single APK rather than offering nothing.
        val single = assets("mangalens-v1.0.3.apk")
        pick(single, "arm64-v8a") shouldBe "mangalens-v1.0.3.apk"
        pick(single, "armeabi-v7a") shouldBe "mangalens-v1.0.3.apk"
    }

    @Test
    fun `x86_64 is not served the x86 build`() {
        // "-x86" is a substring of "-x86_64", so testing in the wrong order
        // hands a 64-bit emulator the 32-bit APK
        val all = assets(
            "mangalens-v1.0.3-x86.apk",
            "mangalens-v1.0.3-x86_64.apk",
            "mangalens-v1.0.3-universal.apk",
        )
        pick(all, "x86_64") shouldBe "mangalens-v1.0.3-x86_64.apk"
        pick(all, "x86") shouldBe "mangalens-v1.0.3-x86.apk"
    }

    @Test
    fun `a checksum or mapping file is never offered as the app`() {
        // The fallback used to be "any asset naming no ABI", so attaching these
        // to a release would have had the updater download and try to install
        // one of them
        val withExtras = assets("mangalens-v1.0.3-arm64-v8a.apk") +
            assets("mangalens-v1.0.3-universal.apk") +
            listOf(
                GitHubAsset("checksums.sha256", "https://dl/checksums.sha256"),
                GitHubAsset("mapping.txt", "https://dl/mapping.txt"),
            )
        pick(withExtras, "armeabi-v7a") shouldBe "mangalens-v1.0.3-universal.apk"
    }

    @Test
    fun `nothing is offered when a release carries no APK`() {
        val empty = listOf(GitHubAsset("mapping.txt", "https://dl/mapping.txt"))
        pick(empty, "arm64-v8a") shouldBe null
        pick(emptyList(), "arm64-v8a") shouldBe null
    }

    @Test
    fun `a foss build only ever updates to a foss build`() {
        val mixed = release + assets("mangalens-v1.0.3-foss.apk")
        pick(mixed, "arm64-v8a", isFoss = true) shouldBe "mangalens-v1.0.3-foss.apk"
        // and never the reverse: the foss APK names no ABI, so it used to be a
        // candidate for the universal fallback
        pick(mixed, "x86_64", isFoss = false) shouldBe "mangalens-v1.0.3-universal.apk"
    }

    @Test
    fun `a foss build with no foss asset is offered nothing rather than the wrong build`() {
        pick(release, "arm64-v8a", isFoss = true) shouldBe null
    }
}
