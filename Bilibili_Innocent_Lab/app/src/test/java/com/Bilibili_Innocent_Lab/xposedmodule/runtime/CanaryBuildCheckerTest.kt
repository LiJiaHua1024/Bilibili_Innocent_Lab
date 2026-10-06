package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CanaryBuildCheckerTest {
    private val sha = "a".repeat(40)
    private fun run(id: Long = 7, number: Long = 3) = JSONObject().apply {
        put("id",id);put("run_number",number);put("head_sha",sha);put("head_branch","main")
        put("event","push");put("status","completed");put("conclusion","success")
        put("path",".github/workflows/canary-build.yml")
        put("head_repository",JSONObject().put("full_name","jichuo1/Bilibili_Innocent_Lab"))
        put("html_url","https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/$id")
    }
    private fun artifact(tag: String = "v1.2.1-canary.3") = JSONObject().apply {
        put("name","Bilibili_Innocent_Lab-$tag-aaaaaaaa");put("expired",false);put("size_in_bytes",100)
        put("workflow_run",JSONObject().put("id",7).put("head_sha",sha))
    }
    private fun runs(vararg values: JSONObject) = JSONObject().put("workflow_runs",JSONArray(values.toList())).toString()
    private fun artifacts(vararg values: JSONObject) = JSONObject().put("artifacts",JSONArray(values.toList())).toString()
    private fun currentRun() = CanaryBuildChecker.parseRuns(runs(run())).single()

    @Test fun validSuccessfulMainPushHasAChannelSpecificVersionAndActionsDestination() {
        val result=CanaryBuildChecker.parseArtifacts(artifacts(artifact()),currentRun())!!
        assertEquals("v1.2.1-canary.3",result.tagName)
        assertTrue(result.actionsBuild);assertTrue(result.prerelease);assertNull(result.apkDownloadUrl)
        assertEquals("https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7",result.htmlUrl)
    }

    @Test fun untrustedOrIncompleteRunsNeverBecomeCanaryUpdates() {
        for ((key,value) in listOf("head_branch" to "other","event" to "pull_request","status" to "in_progress",
            "conclusion" to "failure","path" to ".github/workflows/alpha-release.yml","head_sha" to "not-a-sha")) {
            assertTrue(key,CanaryBuildChecker.parseRuns(runs(run().put(key,value))).isEmpty())
        }
        assertTrue(CanaryBuildChecker.parseRuns(runs(run().put("head_repository",JSONObject().put("full_name","attacker/repo")))).isEmpty())
    }

    @Test fun expiredMismatchedAndUnsignedArtifactNamesAreRejected() {
        for (value in listOf(artifact().put("expired",true),artifact("v1.2.1-alpha.3"),artifact("v1.2.1-canary.4"),
            artifact().put("name","debug.apk"),artifact().put("size_in_bytes",0),
            artifact().put("workflow_run",JSONObject().put("id",8).put("head_sha",sha)))) {
            assertNull(CanaryBuildChecker.parseArtifacts(artifacts(value),currentRun()))
        }
    }

    @Test fun runAndArtifactInspectionRemainBounded() {
        assertEquals(3,CanaryBuildChecker.parseRuns(runs(*Array(20){run((it+1).toLong())})).size)
        val values=Array(21){artifact().put("expired",true)};values[20]=artifact()
        assertNull(CanaryBuildChecker.parseArtifacts(artifacts(*values),currentRun()))
    }

    @Test fun actionUrlsCannotEscapeTheRepositoryOrEmbedCredentials() {
        for (url in listOf("https://evil.invalid/jichuo1/Bilibili_Innocent_Lab/actions/runs/7",
            "https://github.com/other/repo/actions/runs/7","https://user@github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7",
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7?next=evil","http://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7")) {
            assertThrows(IOException::class.java){CanaryBuildChecker.validateRunUrl(url,7)}
        }
    }

    @Test fun versionAndStorageExtendCanaryWithoutChangingTheAlphaChannel() {
        assertEquals(GitHubReleaseChecker.UpdateChannel.CANARY,GitHubReleaseChecker.UpdateChannel.fromStorageValue("canary"))
        assertEquals(GitHubReleaseChecker.UpdateChannel.PREVIEW,GitHubReleaseChecker.UpdateChannel.fromStorageValue("preview"))
        assertEquals(GitHubReleaseChecker.UpdateChannel.STABLE,GitHubReleaseChecker.UpdateChannel.fromStorageValue("unknown"))
        val early=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1-canary.2")!!
        val later=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1-canary.3")!!
        val alpha=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1-alpha.1")!!
        val stable=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1")!!
        assertTrue(early<later);assertTrue(later<alpha);assertTrue(alpha<stable)
        assertEquals(GitHubReleaseChecker.VersionRelation.REMOTE_NEWER,GitHubReleaseChecker.compareVersions("v1.2.1-canary.3","1.2.1-alpha.1",GitHubReleaseChecker.UpdateChannel.CANARY))
        assertEquals(GitHubReleaseChecker.VersionRelation.LOCAL_NEWER,GitHubReleaseChecker.compareVersions("v1.2.1-canary.3","1.2.1"))
    }
}
