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
    private const val DATA_VERSION = 6

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
        val user = userDir(context)
        val marker = File(context.filesDir, "rime/.data_version")
        val installed = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: -1
        if (installed == DATA_VERSION && shared.listFiles()?.isNotEmpty() == true) {
            ensureUserLuaDb(shared, user)
            return shared
        }
        Log.i(TAG, "installing rime data (version $installed -> $DATA_VERSION)")
        shared.deleteRecursively()
        mkdirsOrThrow(shared)
        copyAssets(context, "rime", shared)
        // 顺手清掉上一版部署编出来的表（user/build 里可能留着已经删掉的方案，比如原来 luna/stroke 的
        // 十几 MB）。这里全是可再生的编译产物，清掉不会丢用户词库/自定义短语（那些在 user/*.txt、*.userdb）。
        File(user, "build").deleteRecursively()
        ensureUserLuaDb(shared, user)
        mkdirsOrThrow(marker.parentFile)
        marker.writeText(DATA_VERSION.toString())
        return shared
    }

    /**
     * 雾凇拼音的 lua/lunar.lua 用相对路径 `ReverseDb('lua/lunar.db')` 找库，而 librime 是按
     * **user 目录** 解析相对路径的（桌面版把整个配置目录当 user dir，所以直接放 lua/ 下没问题）。
     * 不复制的话每次按键都会刷 `Error opening reversedb .../user/lua/lunar.db`，农历也就用不了。
     */
    /**
     * 部署前把 **0 字节**的码表文件清掉。
     *
     * librime 用 boost::interprocess mmap 码表；文件是 0 字节时 boost 会抛 C++ 异常，
     * 而部署跑在 librime 的**维护线程**里 —— 那个异常没有 JNI 边界可兜，直接
     * std::terminate → 整个输入法进程 abort（现象：键盘打不开，服务反复重启）。
     * 先清掉，最坏是某个方案缺码表（加载时会被 JNI 的 try/catch 兜住），不会再崩。
     *
     * 正常部署产物不可能是空的，空的只可能来自同步被截断之类的意外。
     * @return 清掉的文件数
     */
    fun dropEmptyTables(user: File): Int {
        val dir = File(user, "build")
        val empties = dir.listFiles { f: File -> f.isFile && f.length() == 0L && f.name.endsWith(".bin") }
            ?: return 0
        for (f in empties) {
            Log.w(TAG, "删除 0 字节码表 ${'$'}{f.name}（留着会让 librime mmap 失败并 abort 进程）")
            f.delete()
        }
        return empties.size
    }

    private fun ensureUserLuaDb(shared: File, user: File) {
        val src = File(shared, "lua/lunar.db")
        if (!src.isFile) return
        val dest = File(user, "lua/lunar.db")
        if (dest.isFile && dest.length() == src.length()) return
        runCatching {
            mkdirsOrThrow(dest.parentFile)
            src.copyTo(dest, overwrite = true)
            Log.i(TAG, "copied lunar.db -> ${dest.absolutePath}")
        }.onFailure { Log.w(TAG, "copy lunar.db failed: ${it.message}") }
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
