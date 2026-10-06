package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.io.IOException
import java.net.URL
import org.json.JSONObject

/** Canary 只读取公开 Actions 元数据；安装包留在 artifacts，客户端不持有 GitHub/Bot 凭据。 */
internal object CanaryBuildChecker {
    const val TELEGRAM_CHANNEL_URL = "https://t.me/Bilibili_Innocent_LabRelease"
    private const val REPOSITORY = "jichuo1/Bilibili_Innocent_Lab"
    private const val API_ROOT = "https://api.github.com/repos/$REPOSITORY"
    private const val MAX_RUNS = 3
    private const val MAX_ARTIFACTS = 20
    private val SHA = Regex("^[0-9a-fA-F]{40}$")
    private val ARTIFACT_NAME = Regex("^Bilibili_Innocent_Lab-(v\\d+\\.\\d+\\.\\d+-canary\\.\\d+)-([0-9a-f]{8})$")
    internal const val RUNS_API = "$API_ROOT/actions/workflows/canary-build.yml/runs?branch=main&status=success&event=push&per_page=5"

    internal data class Run(val id: Long, val number: Long, val sha: String, val url: String)

    fun fetchLatest(): GitHubReleaseChecker.ReleaseInfo = GitHubReleaseChecker.fetchWithFallback(
        listOf(GitHubReleaseChecker.ReleaseEndpoint("GitHub", RUNS_API),
            GitHubReleaseChecker.ReleaseEndpoint("gh-proxy.com", "https://gh-proxy.com/$RUNS_API"))
    ) { endpoint ->
        val proxy = if (endpoint.url.startsWith("https://gh-proxy.com/")) "https://gh-proxy.com/" else ""
        val runs = parseRuns(GitHubReleaseChecker.fetchPayload(endpoint))
        var latest: GitHubReleaseChecker.ReleaseInfo? = null
        for (run in runs) {
            val artifacts = GitHubReleaseChecker.fetchPayload(GitHubReleaseChecker.ReleaseEndpoint(
                endpoint.name, "$proxy$API_ROOT/actions/runs/${run.id}/artifacts?per_page=$MAX_ARTIFACTS"))
            latest = parseArtifacts(artifacts, run)
            if (latest != null) break
        }
        latest ?: throw IOException("No successful unexpired Canary build is available")
    }

    internal fun parseRuns(payload: String): List<Run> {
        try {
            val array = JSONObject(payload).getJSONArray("workflow_runs")
            val result = ArrayList<Run>(MAX_RUNS)
            for (index in 0 until minOf(array.length(), 5)) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("head_branch") != "main" || item.optString("event") != "push" ||
                    item.optString("status") != "completed" || item.optString("conclusion") != "success" ||
                    item.optString("path") != ".github/workflows/canary-build.yml" ||
                    !item.optJSONObject("head_repository")?.optString("full_name").equals(REPOSITORY, true)) continue
                val id = item.optLong("id", -1)
                val number = item.optLong("run_number", -1)
                val sha = item.optString("head_sha")
                if (id <= 0 || number <= 0 || !SHA.matches(sha)) continue
                val url = validateRunUrl(item.optString("html_url"), id)
                result += Run(id, number, sha.lowercase(), url)
                if (result.size == MAX_RUNS) break
            }
            return result
        } catch (error: Exception) {
            throw IOException("Invalid Canary workflow response", error)
        }
    }

    internal fun parseArtifacts(payload: String, run: Run): GitHubReleaseChecker.ReleaseInfo? {
        try {
            val array = JSONObject(payload).getJSONArray("artifacts")
            for (index in 0 until minOf(array.length(), MAX_ARTIFACTS)) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optBoolean("expired", true) || item.optLong("size_in_bytes", 0) <= 0) continue
                val match = ARTIFACT_NAME.matchEntire(item.optString("name")) ?: continue
                if (match.groupValues[2] != run.sha.take(8)) continue
                val version = GitHubReleaseChecker.ReleaseVersion.parse(match.groupValues[1]) ?: continue
                if (version.canaryNumber != run.number) continue
                val owner = item.optJSONObject("workflow_run")
                if (owner != null && (owner.optLong("id", -1) != run.id || owner.optString("head_sha").lowercase() != run.sha)) continue
                return GitHubReleaseChecker.ReleaseInfo(
                    tagName = match.groupValues[1], displayName = match.groupValues[1],
                    releaseNotes = "Canary development build\n\nCommit: `${run.sha}`\n\nActions: ${run.url}",
                    htmlUrl = run.url, apkDownloadUrl = null, prerelease = true, actionsBuild = true)
            }
            return null
        } catch (error: Exception) {
            throw IOException("Invalid Canary artifact response", error)
        }
    }

    internal fun validateRunUrl(value: String, runId: Long? = null): String {
        val url = runCatching { URL(value) }.getOrElse { throw IOException("Invalid Canary build URL", it) }
        val prefix = "/$REPOSITORY/actions/runs/"
        val id = url.path.removePrefix(prefix).toLongOrNull()
        if (url.protocol != "https" || !url.host.equals("github.com", true) || url.userInfo != null ||
            (url.port != -1 && url.port != 443) || !url.path.startsWith(prefix) ||
            id == null || id <= 0 || (runId != null && id != runId) || url.query != null || url.ref != null) {
            throw IOException("Unexpected Canary build URL")
        }
        return value
    }
}
