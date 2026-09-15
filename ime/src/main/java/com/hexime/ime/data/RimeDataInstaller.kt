package com.hexime.ime.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 把打包在 assets/rime 的方案数据释放到应用私有目录。
 * 用版本号标记，避免每次启动重复拷贝。
 */
object RimeDataInstaller {

    private const val TAG = "HeximeData"

    /** 数据版本：改了 assets 内容就 +1，触发重新释放。 */
    private const val DATA_VERSION = 1

    fun sharedDir(context: Context): File = File(context.filesDir, "rime/shared")
    fun userDir(context: Context): File = File(context.filesDir, "rime/user")
    fun logDir(context: Context): File = File(context.filesDir, "rime/log")

    /** 确保数据已释放，返回 shared 目录。可能较慢，应在后台线程调用。 */
    fun ensureInstalled(context: Context): File {
        val shared = sharedDir(context)
        val marker = File(context.filesDir, "rime/.data_version")
        val installed = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: -1
        if (installed == DATA_VERSION && shared.listFiles()?.isNotEmpty() == true) {
            return shared
        }
        Log.i(TAG, "installing rime data (version $installed -> $DATA_VERSION)")
        shared.deleteRecursively()
        shared.mkdirs()
        copyAssets(context, "rime", shared)
        marker.parentFile?.mkdirs()
        marker.writeText(DATA_VERSION.toString())
        return shared
    }

    private fun copyAssets(context: Context, assetDir: String, dest: File) {
        val children = context.assets.list(assetDir) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetDir).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (name in children) copyAssets(context, "$assetDir/$name", File(dest, name))
    }
}
