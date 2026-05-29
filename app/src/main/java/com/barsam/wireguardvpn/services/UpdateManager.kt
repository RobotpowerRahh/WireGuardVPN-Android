package com.barsam.wireguardvpn.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class UpdateAvailable(val versionName: String, val changelog: String, val downloadUrl: String) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class ReadyToInstall(val filePath: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object UpdateManager {

    // TODO: Change this to your actual update info URL
    // Required JSON format:
    // { "versionCode": 2, "versionName": "1.1.0", "downloadUrl": "https://...apk", "sha256": "abc123...", "changelog": "..." }
    private const val UPDATE_CHECK_URL = "https://raw.githubusercontent.com/RobotpowerRahh/WireGuardVPN-Android/main/update.json"

    private fun enforceHttps(url: String): String {
        if (!url.startsWith("https://")) {
            throw SecurityException("Only HTTPS URLs are allowed for updates")
        }
        return url
    }

    suspend fun checkForUpdate(context: Context): UpdateState = withContext(Dispatchers.IO) {
        try {
            val url = enforceHttps(UPDATE_CHECK_URL)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext UpdateState.Error("Server error")
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val remoteVersionCode = json.getInt("versionCode")
            val remoteVersionName = json.getString("versionName")
            val downloadUrl = json.getString("downloadUrl")
            val changelog = json.optString("changelog", "")

            if (downloadUrl.isBlank()) {
                return@withContext UpdateState.UpToDate
            }

            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }

            if (remoteVersionCode > currentVersionCode) {
                UpdateState.UpdateAvailable(remoteVersionName, changelog, downloadUrl)
            } else {
                UpdateState.UpToDate
            }
        } catch (e: SecurityException) {
            UpdateState.Error("Security: ${e.message}")
        } catch (e: Exception) {
            UpdateState.Error("Check failed")
        }
    }

    suspend fun downloadUpdate(context: Context, url: String, expectedSha256: String? = null, onProgress: (Int) -> Unit): UpdateState =
        withContext(Dispatchers.IO) {
            try {
                enforceHttps(url)

                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (dir != null && !dir.exists()) dir.mkdirs()
                val file = File(dir, "WireGuardVPN-update.apk")
                if (file.exists()) file.delete()

                var conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true

                // Handle redirects (GitHub releases use 302s) — enforce HTTPS on redirects too
                var redirects = 0
                while ((conn.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                            conn.responseCode == HttpURLConnection.HTTP_MOVED_TEMP) && redirects < 5
                ) {
                    val nextUrl = conn.getHeaderField("Location")
                    if (nextUrl.startsWith("http://")) {
                        conn.disconnect()
                        throw SecurityException("Redirect to HTTP blocked")
                    }
                    conn.disconnect()
                    conn = URL(nextUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    redirects++
                }

                val contentLength = conn.contentLength.toLong()
                val input = conn.inputStream
                val output = file.outputStream()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int
                val digest = MessageDigest.getInstance("SHA-256")

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (contentLength > 0) {
                        onProgress((totalRead * 100 / contentLength).toInt())
                    }
                }

                output.flush()
                output.close()
                input.close()
                conn.disconnect()

                // Verify SHA-256 hash
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha256 != null && expectedSha256.isNotBlank()) {
                    if (!hash.equals(expectedSha256, ignoreCase = true)) {
                        file.delete()
                        return@withContext UpdateState.Error("Integrity check failed — file may be tampered with")
                    }
                }

                UpdateState.ReadyToInstall(file.absolutePath)
            } catch (e: SecurityException) {
                UpdateState.Error("Security: ${e.message}")
            } catch (e: Exception) {
                UpdateState.Error("Download failed")
            }
        }

    fun installUpdate(context: Context, filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return false
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
