package tachiyomi.data.release

import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        val release = with(json) {
            networkService.client
                .newCall(GET("https://api.github.com/repos/${arguments.repository}/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val downloadLink = getDownloadLink(release = release, isFoss = arguments.isFoss) ?: return null

        return Release(
            version = release.version,
            info = release.info.substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = downloadLink,
        )
    }

    private fun getDownloadLink(release: GithubRelease, isFoss: Boolean): String? =
        downloadLinkFor(release.assets, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), isFoss)

    companion object {
        private const val FOSS = "foss"

        /**
         * The ABI a release asset was built for, read off its file name — the
         * release workflow names them `mangalens-<tag>-<abi>.apk`.
         *
         * `x86_64` must be tested before `x86`, since the shorter one is a
         * substring of the longer.
         */
        private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /**
         * Picks the asset to install on a device reporting [deviceAbi].
         *
         * Releases carry one APK per ABI plus a universal one, so the ABI match
         * is what saves the ~30 MB that the per-ABI split exists for. The
         * universal build is the fallback, and is recognised by having no ABI
         * in its name.
         *
         * Non-APK assets are filtered out first. They used to be eligible as
         * the fallback purely by not naming an ABI, so attaching a checksum or
         * a mapping file to a release would have handed the updater a text file
         * to install.
         */
        internal fun downloadLinkFor(
            assets: List<GitHubAsset>,
            deviceAbi: String,
            isFoss: Boolean,
        ): String? {
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            val isFossAsset = { asset: GitHubAsset -> "-$FOSS" in asset.name }
            if (isFoss) return apks.firstOrNull(isFossAsset)?.downloadLink

            val abiOf = { asset: GitHubAsset -> ABIS.firstOrNull { "-$it" in asset.name } }
            return apks.firstOrNull { !isFossAsset(it) && abiOf(it) == deviceAbi }?.downloadLink
                ?: apks.firstOrNull { !isFossAsset(it) && abiOf(it) == null }?.downloadLink
        }

        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}
