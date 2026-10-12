package com.hexime.ime.data

import android.content.Context

/**
 * 同步用的偏好（token / 仓库 / 上次同步时间）。
 *
 * **单独一个 prefs 文件**：hexime_settings.xml 是要同步上去的文件之一，
 * token 绝不能混进那个文件里（否则 token 会被推到 GitHub）。
 */
object SyncPrefs {

    private const val PREF = "hexime_sync"
    private const val KEY_TOKEN = "token"
    private const val KEY_REPO = "repo"
    private const val KEY_LAST = "last_sync"
    private const val KEY_RELOAD = "phrase_reload_needed"
    private const val KEY_PULLED = "first_pull_done"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun setToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOKEN, value.trim()).apply()
    }

    /** 仓库：`owner/name`；只填名字也行（GithubSync 会用 token 的账号补 owner）。 */
    fun repo(context: Context): String =
        prefs(context).getString(KEY_REPO, "").orEmpty().trim()

    fun setRepo(context: Context, value: String) {
        prefs(context).edit().putString(KEY_REPO, value.trim().removePrefix("https://github.com/").trim('/')).apply()
    }

    /** 上次同步结果（成功/失败都会记，设置页显示）。 */
    fun lastSync(context: Context): String = prefs(context).getString(KEY_LAST, "").orEmpty()

    fun setLastSync(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST, value).apply()
    }

    /**
     * 是否至少成功拉取过一次。
     * **没拉过就不许自动推** —— 刚装好的机器上设置几乎都是默认值，
     * 这时候推上去会把云端的真实设置/词表冲掉（用户改了配色再填 token 就会踩到）。
     */
    fun firstPullDone(context: Context): Boolean = prefs(context).getBoolean(KEY_PULLED, false)

    fun markFirstPullDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_PULLED, true).apply()
    }

    /** 拉取过词表 → 让输入法下次弹键盘时重启引擎把新词读进来。 */
    fun markPhraseReload(context: Context) {
        prefs(context).edit().putBoolean(KEY_RELOAD, true).apply()
    }

    /** 取走并清掉「需要重载词表」标记。 */
    fun consumePhraseReload(context: Context): Boolean {
        val v = prefs(context).getBoolean(KEY_RELOAD, false)
        if (v) prefs(context).edit().putBoolean(KEY_RELOAD, false).apply()
        return v
    }
}
