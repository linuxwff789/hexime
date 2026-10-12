package com.hexime.ime.data

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 内直连 GitHub API 的同步（不依赖 root、不装额外东西）。
 *
 * 同步两个文件到私有仓库：
 *   custom_phrase.txt      自定义词表
 *   hexime_settings.xml    设置（配色/高度/方案/震动/跟手延迟）
 *
 * **本地优先**：云端内容只在本机「还没拉过」时自动应用一次；
 * 平时改动只往上推，想用云端覆盖本机得手动点「下载」。
 *
 * token 存在**另一个** prefs 文件里（[SyncPrefs]），不会跟着设置被推上去。
 */
object GithubSync {

    private const val TAG = "HeximeSync"
    private const val API = "https://api.github.com"
    private const val BRANCH = "main"
    private const val PHRASE = "custom_phrase.txt"
    private const val SETTINGS = "hexime_settings.xml"

    /** 同步结果（给 UI 显示）。 */
    data class Result(val ok: Boolean, val message: String)

    /** 上传：本地 → GitHub。 */
    fun upload(context: Context): Result {
        val (repoRaw, token) = creds(context) ?: return Result(false, "还没填 token / 仓库")
        val repo = try {
            resolveRepo(repoRaw, token)
        } catch (t: Throwable) {
            return Result(false, "仓库解析失败：${t.message}")
        }
        // 词表先和远端做并集再推：词表是只增的数据，整份覆盖会把云端已有的词冲掉
        val phraseFile = File(RimeDataInstaller.userDir(context), "custom_phrase.txt")
        if (phraseFile.isFile) {
            val remote = remoteFileOrNull(repo, token, PHRASE)
            val merged = mergePhrases(
                phraseFile.readText(),
                remote?.toString(Charsets.UTF_8),
            )
            phraseFile.writeText(merged)
        }
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
        val (repoRaw, token) = creds(context) ?: return Result(false, "还没填 token / 仓库")
        val repo = try {
            resolveRepo(repoRaw, token)
        } catch (t: Throwable) {
            return Result(false, "仓库解析失败：${t.message}")
        }
        return try {
            var got = 0
            val phrase = remoteFile(repo, token, PHRASE)
            if (phrase != null) {
                val dst = File(RimeDataInstaller.userDir(context), "custom_phrase.txt")
                dst.parentFile?.mkdirs()
                val local = if (dst.isFile) dst.readText() else ""
                val merged = mergePhrases(local, phrase.toString(Charsets.UTF_8))
                dst.writeText(merged)
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
            SyncPrefs.markFirstPullDone(context)
            Result(true, "已下载 $got 个文件（词表取并集，${stamp()}）")
        } catch (t: Throwable) {
            Log.e(TAG, "download failed", t)
            Result(false, "下载失败：${t.message}")
        }
    }

    // ------------------------------------------------------------ 内部

    private fun creds(context: Context): Pair<String, String>? {
        val token = SyncPrefs.token(context)
        if (token.isBlank()) return null
        val repo = SyncPrefs.repo(context)
        if (repo.isBlank()) return null
        return repo to token
    }

    /** 仓库只填了名字（没有 owner/）→ 用 token 的账号补上。 */
    private fun resolveRepo(repo: String, token: String): String {
        if (repo.contains('/')) return repo
        val (code, text) = request("GET", "$API/user", token, null)
        if (code !in 200..299) throw IllegalStateException("拿不到 token 对应的账号（HTTP $code）")
        val login = JSONObject(text).optString("login")
        if (login.isEmpty()) throw IllegalStateException("token 信息里没有 login")
        return "$login/$repo"
    }

    /** 要同步的本地文件：路径 → 内容。 */
    private fun localFiles(context: Context): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        val phrase = File(RimeDataInstaller.userDir(context), PHRASE)
        if (phrase.isFile) out.add(PHRASE to phrase.readBytes())
        val prefs = File(context.filesDir.parentFile, "shared_prefs/$SETTINGS")
        if (prefs.isFile) out.add(SETTINGS to prefs.readBytes())
        return out
    }

    private fun remoteSha(repo: String, token: String, path: String): String? {
        val (code, text) = request("GET", "$API/repos/$repo/contents/$path?ref=$BRANCH", token, null)
        if (code == 404) return null
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errOf(text)}")
        return JSONObject(text).optString("sha").takeIf { it.isNotEmpty() }
    }

    /** 取远端文件；404 当没有，不抛。 */
    private fun remoteFileOrNull(repo: String, token: String, path: String): ByteArray? =
        runCatching { remoteFile(repo, token, path) }.getOrNull()

    /**
     * 词表并集：本地在前（本地优先保留），远端独有的行追加。
     * 词表格式「词<TAB>码[<TAB>权重]」；注释行去重；空行丢掉。
     */
    private fun mergePhrases(local: String, remote: String?): String {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(raw: String) {
            val line = raw.trimEnd()
            if (line.isBlank()) return
            if (line.startsWith("#")) {
                if (out.none { it == line }) out.add(line)
                return
            }
            val parts = line.split('\t')
            val key = parts.getOrElse(0) { "" }.trim() + "\u0001" + parts.getOrElse(1, { "" }).trim()
            if (seen.add(key)) out.add(line)
        }
        local.lines().forEach { add(it) }
        remote?.lines()?.forEach { add(it) }
        return out.joinToString("\n") + "\n"
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
