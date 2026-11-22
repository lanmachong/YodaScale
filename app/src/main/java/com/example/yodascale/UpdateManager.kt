package com.example.yodascale

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内 OTA 更新：从 GitHub Releases 拉取最新版本并安装。
 *
 * 网络仅用于两件事：
 *   1) 请求 GitHub Releases API 查询最新版本号与 APK 下载地址；
 *   2) 下载该 APK。
 * 不上传任何体重、身体资料或设备信息。
 *
 * 上传仓库后，把 [REPO_OWNER] / [REPO_NAME] 改成实际值即可。
 */
object UpdateManager {
    // ===== 上传 GitHub 后按需修改这里 =====
    private const val REPO_OWNER = "lanmachong"
    private const val REPO_NAME = "YodaScale"
    // ====================================

    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val TIMEOUT_MS = 20_000
    /** 下载总超时；进度连续 STALL_MS 无推进即判定卡死并切换下载源 */
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L
    private const val STALL_MS = 20_000L
    /** GitHub 附件下载备用加速前缀，官方源失败时依次尝试 */
    private val MIRROR_PREFIXES = listOf(
        "", // 官方源
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 由「检查更新」按钮触发。 */
    fun checkForUpdate(activity: Activity) {
        if (REPO_OWNER == "YOUR_GITHUB_NAME") {
            Toast.makeText(activity, "请先在 UpdateManager.kt 中配置 GitHub 仓库名", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val body = httpGet(API_URL)
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").removePrefix("v")
                if (tag.isEmpty()) throw Exception("未找到版本号")

                val current = currentVersion(activity)
                if (!isNewer(tag, current)) {
                    mainHandler.post { toast(activity, "已是最新版本（$current）") }
                    return@Thread
                }

                val apkUrl = findApkUrl(json)
                if (apkUrl == null) {
                    mainHandler.post { toast(activity, "新版本 $tag 未提供 APK 附件") }
                    return@Thread
                }
                val notes = json.optString("body", "").take(500)

                mainHandler.post {
                    AlertDialog.Builder(activity)
                        .setTitle("发现新版本 $tag（当前 $current）")
                        .setMessage(if (notes.isBlank()) "是否下载并安装？" else notes)
                        .setPositiveButton("下载安装") { _, _ -> download(activity, tag, apkUrl) }
                        .setNegativeButton("稍后", null)
                        .show()
                }
            } catch (e: Exception) {
                mainHandler.post { toast(activity, "检查更新失败：${e.message}") }
            }
        }.start()
    }

    private fun download(activity: Activity, tag: String, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("需要安装权限")
                .setMessage("请在系统设置中允许本App「安装未知应用」，否则无法完成更新。")
                .setPositiveButton("去设置") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"),
                        )
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 $tag")
            .setView(progress)
            .setCancelable(false)
            .show()

        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "app-$tag.apk")
        val startTime = System.currentTimeMillis()
        val cancelled = AtomicBoolean(false)
        val lastProgressAt = AtomicLong(System.currentTimeMillis())

        // 看门狗：总超时或进度长时间停滞时强制取消当前连接，触发换源重试
        val watchdog = Thread {
            while (!cancelled.get()) {
                if (System.currentTimeMillis() - startTime > DOWNLOAD_TIMEOUT_MS) break
                if (System.currentTimeMillis() - lastProgressAt.get() > STALL_MS) break
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (!cancelled.get()) {
                cancelled.set(true)
                mainHandler.post { progress.progress = 0 }
            }
        }.apply { isDaemon = true }

        Thread {
            var lastError: String = "未知错误"
            try {
                watchdog.start()
                for (prefix in MIRROR_PREFIXES) {
                    if (cancelled.get()) break
                    val target = prefix + url
                    try {
                        lastError = downloadOnce(target, apk, progress, cancelled, lastProgressAt)
                            ?: "下载不完整"
                        if (cancelled.get()) {
                            lastError = "下载超时或连接停滞"
                            continue
                        }
                        if (apk.length() >= 100_000) {
                            cancelled.set(true)
                            mainHandler.post {
                                dialog.dismiss()
                                install(activity, apk)
                            }
                            return@Thread
                        }
                        lastError = "下载文件异常"
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                    } finally {
                        // 重置停滞计时，给下一个源判定窗口
                        lastProgressAt.set(System.currentTimeMillis())
                    }
                }
                val reason = lastError
                mainHandler.post {
                    dialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("下载失败")
                        .setMessage("$reason\n\n所有下载源均不可用。可改用浏览器打开链接下载，或用系统浏览器访问项目 Releases 页面手动安装。")
                        .setPositiveButton("用浏览器打开") { _, _ ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        .setNegativeButton("知道了", null)
                        .show()
                }
            } finally {
                cancelled.set(true)
            }
        }.start()
    }

    /** 单次下载。返回 null 表示成功，否则返回错误描述。停滞由外部看门狗通过 cancelled 取消。 */
    private fun downloadOnce(
        url: String,
        apk: File,
        progress: ProgressBar,
        cancelled: AtomicBoolean,
        lastProgressAt: AtomicLong,
    ): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "YodaScale-Updater")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) return "HTTP $code"

            val total = conn.contentLength
            var downloaded = 0L
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (!cancelled.get()) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        lastProgressAt.set(System.currentTimeMillis())
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).coerceIn(0, 100).toInt()
                            mainHandler.post { progress.progress = percent }
                        }
                    }
                }
            }
            return if (cancelled.get()) "连接停滞" else null
        } finally {
            conn?.disconnect()
        }
    }

    private fun install(activity: Activity, apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast(activity, "无法打开安装包：${e.message}")
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "YodaScale-Updater")
        conn.connect()
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    private fun findApkUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val url = asset.optString("browser_download_url", "")
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) return url
        }
        return null
    }

    private fun currentVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: PackageManager.NameNotFoundException) {
        "0"
    }

    /** 语义化版本比较：tag 比 current 新返回 true。 */
    private fun isNewer(tag: String, current: String): Boolean {
        fun parse(v: String): List<Int> = v.removePrefix("v")
            .split(".", "-")
            .mapNotNull { it.toIntOrNull() }
        val a = parse(tag)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
