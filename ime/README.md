# hexime IME · 构建与跟手性测试

## 构建

本机无 Android SDK，全部走 GitHub Actions：

- `Build IME` workflow：`scripts/prepare-native.sh ime/src/main/cpp` → `scripts/stage-rime-data.sh ime/src/main/assets/rime` → Gradle 编译。
- 产物：`ime/build/outputs/apk/release/*.apk`（debug key 签名，可直接安装）。

本地安装：

```bash
# 每次 CI 的 debug keystore 不同，覆盖安装要先卸载
adb uninstall com.hexime.ime
adb install -r hexime-release.apk
# 启用并设为默认（也可在系统设置里手动操作）
adb shell ime enable com.hexime.ime/.HeximeService
adb shell ime set com.hexime.ime/.HeximeService
```

---

## 跟手性（响应延迟）怎么测

「跟手性」= 从**手指按下**到**屏幕出现变化**的时间。它由几段组成：

```
触摸 → InputDispatcher → IME 进程(processKey) → librime 引擎 → 候选/组合更新
     → View 重绘 → SurfaceFlinger 合成 → 屏幕
```

其中 librime 引擎本身只占 **0.01–0.2 ms**（见 `PLAN.md` 第 10、11 节），
所以跟手性几乎完全由 UI/渲染/系统调度决定。分下面几种方法测：

### 方法 1：App 内实时延迟（已内置，最方便）

主界面勾选 **「在输入法状态栏显示跟手延迟」**。之后每次按键，输入法状态栏会显示：

```
openfly · 中 · librime 1.17.0 · 跟手 8.3 ms
```

实现：按键事件时记 `t0`，用 `Choreographer.postFrameCallback` 在**下一帧开始**时记 `t1`，
`t1 - t0` 即「按键 → 下一帧」耗时。它不包含最后的合成/显示，但能灵敏反映输入法自身是否跟手。

- 正常应在 **< 16 ms**（60Hz 一帧）；> 30 ms 就要查。
- 反复快速点按，观察是否会因为候选查询/重绘而变高。

### 方法 2：帧耗时 / 掉帧（系统级）

```bash
adb shell dumpsys gfxinfo com.hexime.ime reset
# 在输入法里打一会儿字
adb shell dumpsys gfxinfo com.hexime.ime framestats
```

看 `Draw`/`Prepare`/`Process`/`Execute` 各阶段耗时，以及 Janky frames 比例。
> `gfxinfo` 只统计应用窗口的绘制；IME 窗口通常也被计入。

### 方法 3：Perfetto / Systrace（完整链路）

```bash
# 设备端抓 10 秒
adb shell perfetto -o /data/misc/perfetto-traces/trace -t 10s \
  sched freq idle am wm gfx view input binder_driver
adb pull /data/misc/perfetto-traces/trace
# 用 https://ui.perfetto.dev 打开
```

重点看：`input`（dispatch 时间戳）→ 输入法线程 `doFrame` → `Choreographer#doFrame`
→ `DrawFrame` 的间隔。

### 方法 4：高速摄像（真值 / 端到端）

这是唯一能测到「手指 → 屏幕」全链路的方法：

- 用 240fps / 960fps 慢动作录屏，拍下手指点键的瞬间与屏幕出字/出候选的瞬间；
- 数两帧之间隔了多少帧：`延迟 ≈ 帧数 / 帧率`。
- 480fps 下每帧 ≈ 2.1ms，足够分辨 16ms 级差异。

### 方法 5：显示触摸 + 普通录屏（粗测）

开发者选项打开 **「显示点按操作」**，用 60fps 录屏，数「白点出现」到「候选出现」的帧数
（每帧 16.7ms）。精度低，但可横向对比不同设置。

### 方法 6：输入事件时间戳

```bash
adb shell getevent -lt            # 内核事件时间戳（按下）
adb shell dumpsys input           # 最近输入事件与 dispatch 延迟
```

可与方法 3 的时间戳对齐，定位瓶颈在 `InputDispatcher` 还是应用内。

---

## 建议的目标

| 指标 | 目标 |
|---|---|
| 按键 → 下一帧（App 内） | **< 16 ms** |
| 按键 → 候选显示（端到端） | **< 50 ms** |
| 掉帧率（快速连打） | **< 1%** |
| 引擎 processKey+getContext | 已达标（0.01–0.2 ms） |

## 常见掉跟手的原因（排查清单）

1. 每次按键都在主线程做重活（如重新查询整本词库、JSON/YAML 解析）→ 放到后台/缓存。
2. 候选栏 `removeAllViews` + 重建 View 过多 → 复用 View / 用 RecyclerView。
3. 键盘 View 层级过深、`layout_weight` 嵌套过多 → 拍平布局。
4. 部署（deploy）在主线程执行 → 必须后台。
5. 震动 `vibrate()` 频繁调用 → 已用短时 one-shot，且 0 时可关闭。
