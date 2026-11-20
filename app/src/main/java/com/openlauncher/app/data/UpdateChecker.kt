package com.openlauncher.app.data

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/**
 * In-app update checking against the MarcelQ/openlauncher GitHub releases.
 *
 * GitHub's API cannot tell you an APK's versionCode, so CI writes a marker line
 * into the rolling-release NOTES: `versionCode=7 versionName=0.0.6 commit=<full sha>`.
 * The parser + decision logic here are framework-free (and unit-tested); only
 * [UpdateApi] touches the network, and [ApkDownloader] the Android download/install plumbing.
 */

const val UPDATE_REPO = "MarcelQ/openlauncher"

/** PRIVATE releases-only companion repo for the PLATFORM-SIGNED bench build
 *  (applicationId com.openlauncher.app.aosp). Releases only — never code, and
 *  the Zhantang platform key NEVER touches GitHub (no secret, no artifact, no
 *  CI signing): the APK is built + re-signed LOCALLY via
 *  `C:\Users\Marcel\CarLauncherLab\tools\sign-and-publish-aosp.ps1`. Distribution
 *  is gated by the user's read-only GitHub token (contents:read on this repo
 *  only); without it the channel simply cannot be fetched. */
const val AOSP_REPO = "MarcelQ/openlauncher-aosp"

/** Which rolling GitHub release the app tracks. Mirrors the CI workflow's branch model.
 *  AOSP is the locally-signed platform build (see AOSP_REPO) — offered only on
 *  .aosp builds, and only fetchable with a stored read-only token. */
enum class UpdateChannel(
    val releaseTag: String,
    val assetName: String,
    val label: String,
    val repo: String = UPDATE_REPO,
    val private: Boolean = false
) {
    DEV   ("dev-latest",  "openlauncher-dev-debug.apk",    "dev"),
    STABLE("main-latest", "openlauncher-main-debug.apk",   "main"),
    AOSP  ("aosp-latest", "openlauncher-aosp-debug.apk",   "aosp-signed", AOSP_REPO, true);

    companion object {
        fun fromKey(key: String): UpdateChannel =
            entries.firstOrNull { it.name == key || it.label == key } ?: DEV
    }
}

/** Channels selectable on THIS installed build: the platform-signed AOSP
 *  channel only exists for the `.aosp` applicationId (signature mismatch
 *  would brick-updates for the normal app, and the signed APK must never be
 *  offered to normal installs). Pure — JVM-tested. */
fun availableUpdateChannels(applicationId: String): List<UpdateChannel> =
    if (applicationId.endsWith(".aosp")) UpdateChannel.entries
    else UpdateChannel.entries.filter { !it.private }

/** Bearer header for GitHub, or null for no/blank token (public calls then
 *  send no Authorization header at all). Never log the token itself. */
fun authHeaderFor(token: String): String? =
    token.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

// ── GitHub API models (only the fields we use) ───────────────────────────────
data class GitHubAsset(
    @SerializedName("id")                   val id: Long = 0,
    @SerializedName("name")                 val name: String = "",
    @SerializedName("browser_download_url") val browserDownloadUrl: String = "",
    @SerializedName("size")                 val sizeBytes: Long = 0
)

data class GitHubRelease(
    @SerializedName("tag_name")         val tagName: String = "",
    @SerializedName("target_commitish") val targetCommitish: String = "",
    @SerializedName("body")             val body: String = "",
    @SerializedName("published_at")     val publishedAt: String = "",
    @SerializedName("assets")           val assets: List<GitHubAsset> = emptyList()
)

// ── Normalised view of "what's available" and "what's installed" ─────────────
data class RemoteUpdate(
    val versionCode: Int?,          // null → CI marker missing, fall back to commit compare
    val versionName: String?,
    val commit: String?,            // full or short sha the APK was built from
    val apkUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val notes: String,
    // Private-repo channels (AOSP): browser_download_url is NOT usable without
    // a token on the PUBLIC host, so we keep the asset-API URL (same repo
    // prefix, /releases/assets/{id}) and download it with
    // Accept: application/octet-stream + the user's Bearer token instead.
    val privateAsset: Boolean = false,
    val assetApiUrl: String = ""
)

/** What the downloader must actually hit for this remote (+ optional auth
 *  header). Pure — JVM-tested. Private assets ONLY via the asset API with a
 *  token; if the token is missing the caller must not download at all. */
fun downloadRequestFor(remote: RemoteUpdate, token: String): Pair<String, String?>? {
    val auth = authHeaderFor(token)
    return when {
        !remote.privateAsset -> remote.apkUrl to null
        auth != null && remote.assetApiUrl.isNotEmpty() -> remote.assetApiUrl to auth
        else -> null
    }
}

data class LocalBuild(val versionCode: Int, val versionName: String, val gitSha: String)

enum class UpdateReason { NEWER_VERSION, SAME_VERSION_NEW_BUILD, UNKNOWN_VERSION }

/**
 * How the downloaded APK gets installed (round 10, unit-verified problem):
 * the SPD bench unit's installer UI silently never appears for our
 * ACTION_VIEW+FileProvider handoff, but platform-signed bench builds run at
 * uid 1000 with INSTALL_PACKAGES (declared in the aospDebug manifest ONLY)
 * and can install through a PackageInstaller session themselves. Normal
 * builds never hold that permission and keep the classic installer handoff.
 * Pure — JVM-tested; the ViewModel resolves the permission itself.
 */
enum class InstallRoute { SILENT_SESSION, INSTALLER_INTENT }

fun installRouteFor(installPackagesGranted: Boolean): InstallRoute =
    if (installPackagesGranted) InstallRoute.SILENT_SESSION else InstallRoute.INSTALLER_INTENT

/** Result of comparing [LocalBuild] against [RemoteUpdate]. */
sealed class UpdateDecision {
    data object UpToDate : UpdateDecision()
    data class Available(val reason: UpdateReason) : UpdateDecision()
    data object NoApkAsset : UpdateDecision()
}

/** The state machine the Settings screen renders. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val remote: RemoteUpdate) : UpdateUiState
    data class UpdateAvailable(val remote: RemoteUpdate, val reason: UpdateReason) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data object Installing : UpdateUiState
    data class ReadyToInstall(val remote: RemoteUpdate) : UpdateUiState
    // Carries the remote so "Continue" after granting can resume without re-checking.
    data class NeedsInstallPermission(val remote: RemoteUpdate) : UpdateUiState
}

// ── Pure logic (unit-tested) ─────────────────────────────────────────────────

private val VERSION_CODE_RE = Regex("""versionCode=(\d+)""")
private val VERSION_NAME_RE = Regex("""versionName=([A-Za-z0-9._\-]+)""")
private val COMMIT_RE       = Regex("""commit=([0-9a-fA-F]{7,40})\b""")

/** true when both shas are hex and one is a prefix of the other (short vs full form). */
fun shaMatches(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    if (a == "unknown" || b == "unknown") return false
    val (x, y) = a.lowercase() to b.lowercase()
    return x.length >= 7 && y.length >= 7 && (x.startsWith(y) || y.startsWith(x))
}

/** Extracts the CI marker (versionCode/versionName/commit) from release NOTES. */
fun parseReleaseMarkers(notes: String): Triple<Int?, String?, String?> {
    val code = VERSION_CODE_RE.find(notes)?.groupValues?.get(1)?.toIntOrNull()
    val name = VERSION_NAME_RE.find(notes)?.groupValues?.get(1)
    val commit = COMMIT_RE.find(notes)?.groupValues?.get(1)
    return Triple(code, name, commit)
}

/** The channel's APK asset, else the first asset ending in .apk, else null. */
fun pickApkAsset(assets: List<GitHubAsset>, channel: UpdateChannel): GitHubAsset? =
    assets.firstOrNull { it.name == channel.assetName }
        ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

fun buildRemoteUpdate(release: GitHubRelease, channel: UpdateChannel): RemoteUpdate? {
    val asset = pickApkAsset(release.assets, channel) ?: return null
    val (code, name, commit) = parseReleaseMarkers(release.body)
    // No explicit commit= marker → use targetCommitish when it looks like a sha
    // (the API returns the branch name when the release was made against one).
    val shaFallback = release.targetCommitish.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }
    return RemoteUpdate(
        versionCode = code,
        versionName = name,
        commit      = commit ?: shaFallback,
        apkUrl      = asset.browserDownloadUrl,
        fileName    = asset.name,
        sizeBytes   = asset.sizeBytes,
        publishedAt = release.publishedAt,
        notes       = release.body,
        privateAsset = channel.private,
        assetApiUrl  = if (channel.private && asset.id > 0)
            "https://api.github.com/repos/${channel.repo}/releases/assets/${asset.id}" else ""
    )
}

fun decideUpdate(local: LocalBuild, remote: RemoteUpdate): UpdateDecision {
    val rc = remote.versionCode
    val sameCommit = shaMatches(local.gitSha, remote.commit)
    return when {
        rc != null && rc > local.versionCode -> UpdateDecision.Available(UpdateReason.NEWER_VERSION)
        rc != null && rc < local.versionCode -> UpdateDecision.UpToDate
        sameCommit                           -> UpdateDecision.UpToDate
        // Nothing to compare on (no marker, no sha on either side) — don't nag.
        rc == null && remote.commit == null  -> UpdateDecision.UpToDate
        rc == null                           -> UpdateDecision.Available(UpdateReason.UNKNOWN_VERSION)
        // Equal versionCode but a *different known* remote build sha → re-offer.
        remote.commit != null                -> UpdateDecision.Available(UpdateReason.SAME_VERSION_NEW_BUILD)
        else                                 -> UpdateDecision.UpToDate
    }
}

// ── Network ──────────────────────────────────────────────────────────────────

/** "MarcelQ/openlauncher-aosp" -> ("MarcelQ", "openlauncher-aosp"). */
fun splitRepo(repo: String): Pair<String, String> {
    val i = repo.indexOf('/')
    return if (i < 0) repo to repo else repo.substring(0, i) to repo.substring(i + 1)
}

interface GitHubUpdateApiService {
    @GET("repos/{owner}/{repo}/releases/tags/{tag}")
    suspend fun releaseByTag(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("tag") tag: String,
        @Header("Accept") accept: String = "application/vnd.github+json",
        // Null for public channels (header omitted entirely); Bearer <PAT> for
        // the private AOSP repo. NEVER log this value.
        @Header("Authorization") authorization: String? = null
    ): GitHubRelease
}

object UpdateApi {
    private val client = okhttp3.OkHttpClient.Builder().build()

    val service: GitHubUpdateApiService = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubUpdateApiService::class.java)
}
