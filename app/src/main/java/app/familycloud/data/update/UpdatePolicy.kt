package app.familycloud.data.update

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String?)

internal data class AppVersion(val name: String, val code: Long)

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

    fun versionFromNotes(notes: String): AppVersion {
        val values = mutableMapOf<String, String>()
        notes.lineSequence().forEach { raw ->
            val line = raw.trim()
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator)
            if (key == "versionName" || key == "versionCode") values[key] = line.substring(separator + 1).trim()
        }
        val name = values["versionName"] ?: throw UpdateFailure(UpdateProblem.Release)
        val code = values["versionCode"]?.toLongOrNull() ?: throw UpdateFailure(UpdateProblem.Release)
        parse(name)
        if (code !in 1..Int.MAX_VALUE) throw UpdateFailure(UpdateProblem.Release)
        return AppVersion(name, code)
    }

    fun canonical(raw: String): String {
        val name = raw.removeSuffix("-debug")
        parse(name)
        return name
    }

    /** Сравнивает объявленную версию с установленной. APK для этого не нужен. */
    fun isNewer(installedName: String, installedCode: Long, announced: AppVersion): Boolean {
        if (installedCode <= 0) throw UpdateFailure(UpdateProblem.InvalidApk)
        val comparison = parse(announced.name).compareTo(parse(canonical(installedName)))
        if (comparison <= 0) return false
        if (announced.code <= installedCode) throw UpdateFailure(UpdateProblem.Release)
        return true
    }

    fun matches(actualName: String, actualCode: Long, announced: AppVersion): Boolean =
        canonical(actualName) == announced.name && actualCode == announced.code

    fun validateIdentity(
        installedPackage: String,
        downloadedPackage: String,
        installedSigners: Set<String>,
        downloadedSigners: Set<String>,
    ) {
        if (installedPackage != downloadedPackage || installedSigners.isEmpty() ||
            installedSigners != downloadedSigners) throw UpdateFailure(UpdateProblem.Identity)
    }

    private fun parse(name: String): SemanticVersion {
        val pieces = name.split('.')
        if (pieces.size != 3 || pieces.any { !PART.matches(it) }) throw UpdateFailure(UpdateProblem.Release)
        return SemanticVersion(pieces[0].toInt(), pieces[1].toInt(), pieces[2].toInt())
    }

    private data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }
    }

    private val PART = Regex("""0|[1-9]\d{0,4}""")
}

internal enum class UpdateProblem { Network, Release, Asset, InvalidApk, Identity, Install }
internal class UpdateFailure(val problem: UpdateProblem) : Exception()
