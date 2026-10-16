package com.usblan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check against the GitHub releases of this repo.
 *
 * Uses /releases (not /releases/latest, which skips prereleases) and takes
 * the first entry, which GitHub returns newest first. All network IO must run
 * off the main thread.
 */
object Updater {

    private const val RELEASES_URL = "https://api.github.com/repos/sebialex1/usb-lan/releases"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val UPDATES_DIR = "updates"

    data class Release(val tag: String, val version: String, val apkUrl: String?, val apkName: String?)

    sealed class Result {
        data class UpToDate(val installed: String, val remote: String) : Result()
        data class Downloaded(val release: Release, val file: File) : Result()
        data class NoApk(val release: Release) : Result()
    }

    fun installedVersion(context: Context): String =
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Blocking: fetch, compare and (if newer) download. Throws IOException on network errors. */
    fun check(context: Context, onProgress: (String) -> Unit): Result {
        val installed = installedVersion(context)
        val release = fetchNewestRelease()
        if (compareVersions(release.version, installed) <= 0) {
            return Result.UpToDate(installed, release.version)
        }
        val url = release.apkUrl ?: return Result.NoApk(release)
        onProgress("Downloading ${release.tag}...")
        val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest download
        val safeName = (release.apkName ?: "usb-lan-${release.tag}.apk")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, safeName)
        download(url, target)
        return Result.Downloaded(release, target)
    }

    /** API 26+: the user must allow this app to install packages. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchInstaller(context: Context, apk: File) {
        val uri = UpdateFileProvider.getUriForFile(context, apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun fetchNewestRelease(): Release {
        val body = httpGet(RELEASES_URL)
        val releases = JSONArray(body)
        if (releases.length() == 0) throw IOException("No releases published")
        val r = releases.getJSONObject(0)
        val tag = r.getString("tag_name")
        val assets = r.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var apkName: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url").takeIf { it.isNotEmpty() }
                apkName = name
                break
            }
        }
        return Release(tag, tag.removePrefix("v").removePrefix("V"), apkUrl, apkName)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // assets redirect to another https host
            setRequestProperty("User-Agent", "usb-lan-android/${Build.VERSION.SDK_INT}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        val c = open(url)
        c.setRequestProperty("Accept", "application/octet-stream")
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Download failed: HTTP $code")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() == 0L) throw IOException("Downloaded file is empty")
            if (!tmp.renameTo(target)) throw IOException("Could not save update")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Compare dotted versions numerically ("0.10.0" > "0.9.1"); non-numeric parts count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '+').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '+').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
