package com.hexime.ime.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 把打包在 assets/rime 的方案数据释放到应用私有目录。
 * 用版本号标记，避免每次启动重复拷贝。
 *
 * 注意：这里的失败必须**抛出可读的 IOException**，不能让它变成 ENOENT 悄悄往上冒 ——
 * 之前 mkdirs() 失败（files/ 目录属主不对）没检查，后面 FileOutputStream 直接
 * FileNotFoundException，在后台线程里没人接 → 整个进程被杀（表现为"一打开就闪退"）。
 */
object RimeDataInstaller {

    private const val TAG = "HeximeData"

    /** 数据版本：改了 assets 内容就 +1，触发重新释放。 */
    private const val DATA_VERSION = 2

    fun sharedDir(context: Context): File = File(context.filesDir, "rime/shared")
    fun userDir(context: Context): File = File(context.filesDir, "rime/user")
    fun logDir(context: Context): File = File(context.filesDir, "rime/log")

    /**
     * 数据已是当前版本、且已经释放过（= 上次部署过）。
     * 用它决定「要不要 deploy」——没变就别部署，每次弹键盘能省 100~300ms。
     */
    fun isDeployed(context: Context): Boolean {
        val marker = File(context.filesDir, "rime/.data_version")
        val installed = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: -1
        return installed == DATA_VERSION && sharedDir(context).listFiles()?.isNotEmpty() == true
    }

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
        mkdirsOrThrow(shared)
        copyAssets(context, "rime", shared)
        mkdirsOrThrow(marker.parentFile)
        marker.writeText(DATA_VERSION.toString())
        return shared
    }

    private fun copyAssets(context: Context, assetDir: String, dest: File) {
        val children = context.assets.list(assetDir) ?: return
        if (children.isEmpty()) {
            mkdirsOrThrow(dest.parentFile)
            context.assets.open(assetDir).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            return
        }
        mkdirsOrThrow(dest)
        for (name in children) copyAssets(context, "$assetDir/$name", File(dest, name))
    }

    /** mkdirs 失败就当场报出来（带上路径和原因），别等到后面 open 才炸。 */
    private fun mkdirsOrThrow(dir: File?) {
        if (dir == null || dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException(
                "建不了目录 ${dir.absolutePath}" +
                    "（exists=${dir.exists()} canWrite=${dir.parentFile?.canWrite()}）",
            )
        }
    }
}
