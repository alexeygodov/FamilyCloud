package app.familycloud.data.update

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String?)

internal object UpdatePolicy {
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val RELEASE_API = "https://api.github.com/repos/alexeygodov/FamilyCloud/releases/tags/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/alexeygodov/FamilyCloud/releases/download/latest/"

    fun selectAsset(assets: List<ReleaseAsset>, debug: Boolean): ReleaseAsset {
        val name = if (debug) "FamilyCloudApp-debug.apk" else "FamilyCloudApp.apk"
        val asset = assets.singleOrNull { it.name == name } ?: throw UpdateFailure(UpdateProblem.Asset)
        if (asset.size !in 1..MAX_APK_BYTES || asset.url != DOWNLOAD_PREFIX + name ||
            (asset.digest != null && !asset.digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))) {
            throw UpdateFailure(UpdateProblem.Asset)
        }
        return asset
    }

    fun validateIdentity(
        installedPackage: String,
        downloadedPackage: String,
        installedSigners: Set<String>,
        downloadedSigners: Set<String>,
    ) {
        if (installedPackage != downloadedPackage || installedSigners.isEmpty() ||
            installedSigners != downloadedSigners) throw UpdateFailure(UpdateProblem.Identity)
    }

    fun isNewer(installed: Long, downloaded: Long): Boolean {
        if (installed <= 0 || downloaded <= 0) throw UpdateFailure(UpdateProblem.InvalidApk)
        return downloaded > installed
    }
}

internal enum class UpdateProblem { Network, Release, Asset, InvalidApk, Identity, Install }
internal class UpdateFailure(val problem: UpdateProblem) : Exception()
