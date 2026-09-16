package com.hexime.ime.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    private const val DATA_VERSION = 3

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

    // ---------------------------------------------------------------- 部署标记
    //
    // librime 的 deploy() 是「检查 + 补齐编译产物」，进程每次启动都调一遍纯属浪费
    // （首次要编译词库，之后只是全量 stat 一遍）。这里用三件事做戳：
    //   1. assets 数据版本；2. 安装包更新时间（重装/升级 = 换包）；
    //   3. user 目录里 yaml 的最新 mtime（用户手改了方案要重新部署）。
    // 三者都没变、且 build/ 里有 .bin 产物，就跳过 deploy。

    private fun deployMarker(context: Context): File =
        File(context.filesDir, "rime/.deployed")

    /** 需要重新部署吗？ */
    fun needsDeploy(context: Context): Boolean {
        if (!hasBuildArtifacts(context)) return true
        val marker = deployMarker(context)
        if (!marker.exists()) return true
        return marker.readText().trim() != deployStamp(context)
    }

    /** 记下本次已部署的戳。 */
    fun markDeployed(context: Context) {
        val marker = deployMarker(context)
        marker.parentFile?.mkdirs()
        runCatching { marker.writeText(deployStamp(context)) }
    }

    /** 手动触发重新部署（设置页按钮）时清掉戳。 */
    fun invalidateDeploy(context: Context) {
        runCatching { deployMarker(context).delete() }
    }

    private fun hasBuildArtifacts(context: Context): Boolean {
        val build = File(userDir(context), "build")
        return build.listFiles()?.any { it.name.endsWith(".bin") } == true
    }

    private fun deployStamp(context: Context): String {
        val version = runCatching { packageUpdateTime(context) }.getOrDefault(0L)
        return "$DATA_VERSION-$version-${newestYaml(context, userDir(context))}"
    }

    private fun packageUpdateTime(context: Context): Long {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        return info.lastUpdateTime
    }

    /** user 目录（递归一层）里 .yaml/.txt 的最新修改时间，没有则 0。 */
    private fun newestYaml(context: Context, dir: File): Long {
        var newest = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 64) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (child.name.endsWith(".yaml") || child.name.endsWith(".txt")) {
                    if (child.lastModified() > newest) newest = child.lastModified()
                }
            }
        }
        return newest
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
