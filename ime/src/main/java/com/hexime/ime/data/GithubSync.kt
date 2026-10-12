package com.hexime.ime.data

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 内直连 GitHub API 的同步（不依赖 Termux/root）。
 *
 * 同步的文件和 Termux 那个备份脚本**完全一致**（同一仓库、同一路径），
 * 所以两条路可以互相接：
 *   custom_phrase.txt      自定义词表
 *   hexime_settings.xml    设置（配色/高度/方案/震动/跟手延迟）
 *
 * token 存在**另一个** prefs 文件里（[SyncPrefs]），不会跟着设置被推上去。
 */
object GithubSync {

    private const val TAG = "HeximeSync"
    private const val API = "https://api.github.com"
    private const val BRANCH = "main"

    /** 同步结果（给 UI 显示）。 */
    data class Result(val ok: Boolean, val message: String)

    /** 上传：本地 → GitHub。 */
    fun upload(context: Context): Result {
        val (repo, token) = creds(context) ?: return Result(false, "还没填 token")
        val files = localFiles(context)
        return try {
            for ((path, bytes) in files) {
                val sha = remoteSha(repo, token, path)
                val body = JSONObject()
                    .put("message", "hexime 同步 ${stamp()}：$path")
                    .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("branch", BRANCH)
                if (sha != null) body.put("sha", sha)
                val (code, text) = request("PUT", "$API/repos/$repo/contents/$path", token, body.toString())
                if (code !in 200..299) {
                    return Result(false, "上传 $path 失败：HTTP $code ${errOf(text)}")
                }
            }
            val msg = "已上传 ${files.size} 个文件 → $repo（${stamp()}）"
            Log.i(TAG, msg)
            Result(true, msg)
        } catch (t: Throwable) {
            Log.e(TAG, "upload failed", t)
            Result(false, "上传失败：${t.message}")
        }
    }

    /** 下载：GitHub → 本地（词表直接写文件，设置解析后走 setter 落盘）。 */
    fun download(context: Context): Result {
        val (repo, token) = creds(context) ?: return Result(false, "还没填 token")
        return try {
            var got = 0
            val phrase = remoteFile(repo, token, "custom_phrase.txt")
            if (phrase != null) {
                val dst = File(RimeDataInstaller.userDir(context), "custom_phrase.txt")
                dst.parentFile?.mkdirs()
                dst.writeBytes(phrase)
                // 让输入法下次弹键盘时重启引擎，把新词读进来
                SyncPrefs.markPhraseReload(context)
                got++
            }
            val settings = remoteFile(repo, token, "hexime_settings.xml")
            if (settings != null) {
                applySettingsXml(context, String(settings, Charsets.UTF_8))
                got++
            }
            if (got == 0) return Result(false, "远端没有这两个文件（先上传一次？）")
            Result(true, "已下载 $got 个文件（${stamp()}）")
        } catch (t: Throwable) {
            Log.e(TAG, "download failed", t)
            Result(false, "下载失败：${t.message}")
        }
    }

    // ------------------------------------------------------------ 内部

    private fun creds(context: Context): Pair<String, String>? {
        val token = SyncPrefs.token(context)
        if (token.isBlank()) return null
        return SyncPrefs.repo(context) to token
    }

    /** 要同步的本地文件：路径 → 内容。 */
    private fun localFiles(context: Context): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        val phrase = File(RimeDataInstaller.userDir(context), "custom_phrase.txt")
        if (phrase.isFile) out.add("custom_phrase.txt" to phrase.readBytes())
        val prefs = File(context.filesDir.parentFile, "shared_prefs/hexime_settings.xml")
        if (prefs.isFile) out.add("hexime_settings.xml" to prefs.readBytes())
        return out
    }

    private fun remoteSha(repo: String, token: String, path: String): String? {
        val (code, text) = request("GET", "$API/repos/$repo/contents/$path?ref=$BRANCH", token, null)
        if (code == 404) return null
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errOf(text)}")
        return JSONObject(text).optString("sha").takeIf { it.isNotEmpty() }
    }

    private fun remoteFile(repo: String, token: String, path: String): ByteArray? {
        val (code, text) = request("GET", "$API/repos/$repo/contents/$path?ref=$BRANCH", token, null)
        if (code == 404) return null
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errOf(text)}")
        val b64 = JSONObject(text).optString("content").replace("\n", "").replace("\r", "")
        return Base64.decode(b64, Base64.DEFAULT)
    }

    /** SharedPreferences 的 XML 是固定格式，正则够用（我们只同步自己写的这几个 key）。 */
    private fun applySettingsXml(context: Context, xml: String) {
        fun str(key: String) =
            Regex("<string name=\"$key\">(.*?)</string>").find(xml)?.groupValues?.get(1)
        fun int(key: String) =
            Regex("<int name=\"$key\" value=\"(-?\\d+)\"\\s*/>").find(xml)?.groupValues?.get(1)?.toIntOrNull()
        fun bool(key: String) =
            Regex("<boolean name=\"$key\" value=\"(true|false)\"\\s*/>").find(xml)
                ?.groupValues?.get(1)?.toBoolean()
        str("palette_id")?.let { HeximeSettings.setPaletteId(context, it) }
        str("schema_id")?.let { HeximeSettings.setSchemaId(context, it) }
        int("keyboard_height_dp")?.let { HeximeSettings.setKeyboardHeightDp(context, it) }
        int("vibration_percent")?.let { HeximeSettings.setVibrationPercent(context, it) }
        bool("show_latency")?.let { HeximeSettings.setShowLatency(context, it) }
    }

    private fun request(method: String, url: String, token: String, body: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "hexime-ime")
            connectTimeout = 15000
            readTimeout = 25000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** GitHub 的错误信息在 {"message": "..."} 里。 */
    private fun errOf(text: String): String = runCatching {
        JSONObject(text).optString("message")
    }.getOrDefault(text.take(120))

    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date())
}
