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

// Upstream has no linear fade API. Build its pinned source with the reviewed public extension.
// Both modules are substituted together; the original JitPack artifacts are never mixed in.
val lumenRevision = "91e31dce23dda35d2fd6d2411d0aac3079e3c46a"
check(file("gradle/libs.versions.toml").readText().contains("lumen-engine = \"$lumenRevision\"")) {
    "Update the Lumen source pin, archive checksum and extension together with the catalog."
}
fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
val lumenPatch = file("gradle/lumen/linear-fade.patch")
val lumenPatchHash = digest(lumenPatch.readBytes())
val lumenCache = file(".gradle/lumen-source").apply { mkdirs() }
val lumenSource = lumenCache.resolve("${lumenRevision.take(12)}-${lumenPatchHash.take(12)}")
RandomAccessFile(lumenCache.resolve("prepare.lock"), "rw").channel.use { channel ->
    channel.lock().use {
        if (!lumenSource.resolve(".prepared").isFile) {
            val archive = lumenCache.resolve("$lumenRevision.zip")
            val archiveHash = "300be73940e04854b2a893af1aea57f711c6ea18c590c556c261e6d79b8c67c6"
            if (!archive.isFile) {
                check(!gradle.startParameter.isOffline) {
                    "Lumen source is not cached. Run Gradle once without --offline to fetch the pinned archive."
                }
                val connection = URI("https://codeload.github.com/jichuo1/LumenCoacervationEngine/zip/$lumenRevision")
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
            val builder = ProcessBuilder("git", "-c", "core.longpaths=true", "apply", "--ignore-space-change", "--whitespace=error-all", lumenPatch.absolutePath)
                .directory(lumenSource).redirectErrorStream(true)
            // This is a source patch, not a write to the host repository's index or history.
            builder.environment()["GIT_CEILING_DIRECTORIES"] = lumenCache.canonicalPath
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Could not apply the pinned Lumen extension:\n$output" }
            // Make diagnostics identify this local extension instead of claiming stock 1.1.0.
            val properties = lumenSource.resolve("gradle.properties")
            properties.writeText(properties.readText().replace("lumen.version=1.1.0",
                "lumen.version=$lumenRevision-linear.${lumenPatchHash.take(12)}"))
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
