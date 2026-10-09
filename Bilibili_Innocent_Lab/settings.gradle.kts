import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://jitpack.io/")
    }
}

plugins {
    id("com.highcapable.gropify") version "1.0.2"
}

gropify {
    rootProject {
        common {
            isEnabled = false
        }
    }
}

rootProject.name = "Bilibili_Innocent_Lab"

include(":app")

// Build the pinned release with public extensions for the host's original fade, motion and glass.
// Both modules are substituted together; the original JitPack artifacts are never mixed in.
val lumenRevision = "1.2.2"
check(file("gradle/libs.versions.toml").readText().contains("lumen-engine = \"$lumenRevision\"")) {
    "Update the Lumen source pin, archive checksum and extension together with the catalog."
}
fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
val lumenPatches = listOf("linear-fade.patch", "motion-parity.patch", "surface-parity.patch").map { file("gradle/lumen/$it") }
val lumenPatchHash = digest(lumenPatches.fold(ByteArray(0)) { bytes, patch -> bytes + patch.readBytes() })
val lumenCache = file(".gradle/lumen-source").apply { mkdirs() }
val lumenSource = lumenCache.resolve("${lumenRevision.take(12)}-${lumenPatchHash.take(12)}")
RandomAccessFile(lumenCache.resolve("prepare.lock"), "rw").channel.use { channel ->
    channel.lock().use {
        if (!lumenSource.resolve(".prepared").isFile) {
            val archive = lumenCache.resolve("$lumenRevision.zip")
            val archiveHash = "c736b882977584dd79fa4e320f0353ab05027fad284ee8b024af9ac2ec4fc4e6"
            if (!archive.isFile) {
                check(!gradle.startParameter.isOffline) {
                    "Lumen source is not cached. Run Gradle once without --offline to fetch the pinned archive."
                }
                val connection = URI("https://codeload.github.com/jichuo1/LumenCoacervationEngine/zip/refs/tags/$lumenRevision")
                    .toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
                val bytes = connection.getInputStream().use { it.readBytes() }
                check(digest(bytes) == archiveHash) { "Lumen archive checksum mismatch." }
                archive.writeBytes(bytes)
            }
            check(digest(archive.readBytes()) == archiveHash) { "Cached Lumen archive checksum mismatch." }
            // A failed preparation leaves no success marker and can be retried without deleting files.
            lumenSource.mkdirs()
            val root = lumenSource.canonicalFile.toPath()
            val prefix = "LumenCoacervationEngine-$lumenRevision/"
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(entry.name.startsWith(prefix)) { "Unexpected archive root." }
                    val target = root.resolve(entry.name.removePrefix(prefix)).normalize()
                    check(target.startsWith(root)) { "Archive entry leaves the source directory." }
                    if (entry.isDirectory) Files.createDirectories(target) else {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target).use { zip.copyTo(it) }
                    }
                }
            }
            val builder = ProcessBuilder(listOf("git", "-c", "core.longpaths=true", "apply", "--ignore-space-change", "--whitespace=error-all") + lumenPatches.map { it.absolutePath })
                .directory(lumenSource).redirectErrorStream(true)
            // This is a source patch, not a write to the host repository's index or history.
            builder.environment()["GIT_CEILING_DIRECTORIES"] = lumenCache.canonicalPath
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Could not apply the pinned Lumen extension:\n$output" }
            // Make diagnostics identify the reviewed extension of the release.
            val properties = lumenSource.resolve("gradle.properties")
            properties.writeText(properties.readText().replace("lumen.version=$lumenRevision",
                "lumen.version=$lumenRevision-host.${lumenPatchHash.take(12)}"))
            lumenSource.resolve(".prepared").writeText(lumenPatchHash)
        }
        check(lumenSource.resolve(".prepared").readText() == lumenPatchHash) { "Lumen source cache key collision." }
    }
}
includeBuild(lumenSource) {
    name = "lumen"
    dependencySubstitution {
        for (artifact in listOf("lumen-engine", "lumen-motion")) {
            substitute(module("com.github.jichuo1.LumenCoacervationEngine:$artifact:$lumenRevision"))
                .using(project(":$artifact"))
        }
    }
}
