package com.american2day.truespeed

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transparent, opt-in OTA. We fetch a tiny JSON manifest, compare versionCode,
 * and — only if the user taps "Download & install" — download the APK and hand
 * it to Android's system installer. We never install silently. If the server is
 * unreachable the app keeps working; this is a no-op on failure.
 *
 * update.json format (served at UPDATE_URL):
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1",
 *   "apkUrl": "https://american2day.com/TrueSpeed/TrueSpeed-1.1.apk",
 *   "notes": "What changed in this version.",
 *   "minAndroidSdk": 21
 * }
 */
object UpdateChecker {

    const val UPDATE_URL = "https://american2day.com/TrueSpeed/update.json"

    private val main = Handler(Looper.getMainLooper())

    /** @param announceNoUpdate show a toast when already up to date / on error. */
    fun check(activity: Activity, announceNoUpdate: Boolean) {
        Thread {
            try {
                val json = httpGet(UPDATE_URL)
                val obj = org.json.JSONObject(json)
                val remoteCode = obj.getInt("versionCode")
                val remoteName = obj.optString("versionName", "?")
                val apkUrl = obj.getString("apkUrl")
                val notes = obj.optString("notes", "")
                val current = BuildConfig.VERSION_CODE

                main.post {
                    if (activity.isFinishing) return@post
                    if (remoteCode > current) {
                        promptInstall(activity, remoteName, notes, apkUrl)
                    } else if (announceNoUpdate) {
                        toast(activity, activity.getString(R.string.update_none))
                    }
                }
            } catch (e: Exception) {
                if (announceNoUpdate) {
                    main.post {
                        if (!activity.isFinishing) {
                            toast(activity, activity.getString(R.string.update_failed))
                        }
                    }
                }
            }
        }.start()
    }

    private fun promptInstall(activity: Activity, name: String, notes: String, apkUrl: String) {
        val msg = buildString {
            append("Version ").append(name)
            if (notes.isNotBlank()) append("\n\n").append(notes)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available)
            .setMessage(msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.update_download) { _, _ ->
                downloadAndInstall(activity, apkUrl, name)
            }
            .show()
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String, name: String) {
        toast(activity, "Downloading…")
        Thread {
            try {
                val dir = File(activity.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val out = File(dir, "TrueSpeed-$name.apk")
                URL(apkUrl).openStream().use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                main.post { if (!activity.isFinishing) launchInstaller(activity, out) }
            } catch (e: Exception) {
                main.post {
                    if (!activity.isFinishing) toast(activity, activity.getString(R.string.update_failed))
                }
            }
        }.start()
    }

    private fun launchInstaller(activity: Activity, apk: File) {
        // Android 8+ gates sideload installs behind a per-app permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            toast(activity, activity.getString(R.string.install_needed))
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName),
                )
            )
            return
        }
        val uri = FileProvider.getUriForFile(
            activity, activity.packageName + ".fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
