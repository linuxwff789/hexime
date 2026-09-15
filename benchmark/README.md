# hexime-benchmark

RIME (librime) 在 Android 真机上的性能基准 App。**独立于正式输入法**，只用来回答一个问题：

> librime 在 Android 上的延迟/内存/部署耗时到底能不能接受？

## 测什么

| 指标 | 方法 |
|---|---|
| 初始化耗时 | `RimeInitialize` 前后计时 |
| 部署耗时 | `RimeStartMaintenance(true)` + `RimeJoinMaintenance()` |
| 按键延迟 | `RimeSimulateKeySequence` + `RimeGetContext`，统计 P50/P95/P99 |
| 内存 | `Debug.getMemoryInfo().totalPss`（初始化后 / 部署后 / 压测后） |
| 候选质量 | 打印若干测试码的候选，人工核对 |
| `.so` 体积 | APK 内 `libhexime_bench.so` |

## 构建方式（重要）

本机没有 Android SDK/NDK，全部走 **GitHub Actions**：

1. `scripts/prepare-native.sh` 从 `osfans/trime@v3.3.12` 拉取并组装 librime 及其依赖
   （librime / OpenCC / snappy / glog / leveldb / marisa-trie / yaml-cpp / boost / 插件）。
2. `scripts/stage-rime-data.sh` 下载 RIME 方案数据（prelude + luna-pinyin）到 `assets/rime`。
3. Gradle + NDK 编译 `libhexime_bench.so` 并打包 APK。
4. tag 时发布到 Release，真机下载安装。

> 复用 trime 的原生构建脚本是因为它是目前 Android 上经过验证的 librime 构建方案；
> 自己从零写 CMake 交叉编译依赖成本高得多。注意 trime 构建脚本为 GPL-3.0。

## 本地（无 SDK）开发

只能改代码、跑 lint、提交；编译看 CI。真机安装：

```bash
# 下载 Release 里的 app-release.apk
adb install -r app-release.apk     # 或在手机上直接点安装
```

## 手动触发 CI

仓库 push 后在 GitHub → Actions → **Build Benchmark** → Run workflow。
或打 tag：`git tag bench-v0.1 && git push origin bench-v0.1`，会自动发 Release。
