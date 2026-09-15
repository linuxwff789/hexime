# Hexime 输入法 · 开发方案

> 项目名 `hexime`（鹤 = 小鹤音形）为占位名，可随时改。
> 本文档为需求确认后的方案基线。最后更新：本轮对话。

---

## 0. 需求基线（已确认）

| 维度 | 决定 |
|---|---|
| 平台 | **Android**（最低 API 29 / Android 10+） |
| 输入方案 | 中文**拼音**、**小鹤音形（纯正音形方案）**、**纯英文** |
| UI | **全键盘 QWERTY**，极简，不做皮肤系统 |
| 引擎 | **Kotlin UI + C++/JNI 核心**；词库直接复用 RIME 的 yaml/txt；**先做性能基准再定引擎** |
| 用户词库 | **自动学习新词 + 自动调频（可开关）** |
| 同步 | **WebDAV / 坚果云**（可选云功能） |
| 隐私 | **本地优先**，云功能默认关闭 |
| 核心诉求 | 用户词库学习、云同步、**极简 / 低延迟 / 省电** |
| 增强功能 | **一律不做**（Emoji 面板、剪贴板历史、滑行、语音等暂缓） |
| 项目性质 | 个人自用 |
| 现状 | 完全从零开始 |
| 编译 | **GitHub Actions 编译 → GitHub Release 下载 APK 真机调试**（本机无 Java/Gradle/Android SDK） |
| 下一步 | **M0：RIME 性能基准 demo** |

---

## 1. 目标与非目标

### 目标
1. 一个能日常使用的中英+小鹤音形输入法，打字跟手、后台占用低。
2. 词库基于 RIME 生态（`.yaml` / `.dict.yaml` / `.txt`），可长期维护、可迁移。
3. 用户词库自动学习，且完全存本地；同步是可插拔的 WebDAV。
4. 架构清晰，便于逐步加功能，而不是一次性堆完。

### 非目标（当前阶段）
- 多平台、皮肤商店、账号体系、在线预测、语音/手写。
- 九宫格、滑行输入。
- 商业级多语言（日韩）。

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────┐
│ UI 层 (Kotlin)                                            │
│  KeyboardView / CandidateBar / 极简主题 / 手势             │
├─────────────────────────────────────────────────────────┤
│ IME Service 层 (Kotlin, InputMethodService)               │
│  InputMethodService · InputConnection 管理 · 生命周期      │
│  · 输入状态机（中/英/符号）· 候选提交 · 上屏                │
├─────────────────────────────────────────────────────────┤
│ 引擎抽象层 (Kotlin interface)                              │
│  InputEngine { processKey, getCandidates, select, learn } │
│  ← 可替换实现：RimeEngine / NativeEngine / 未来其他         │
├─────────────────────────────────────────────────────────┤
│ 引擎实现层 (C++/JNI)                                       │
│  rime_bridge (librime C API) 或 hexime_core (自研)         │
│  · 查询/候选/排序 · 用户词频 · 简繁/模糊 · 方案切换          │
├─────────────────────────────────────────────────────────┤
│ 数据层                                                     │
│  词库导入(RIME yaml/txt → 二进制索引) · 用户词库 · 设置       │
│  · Room/SQLite 存配置与用户词 · mmap 只读主词库              │
├─────────────────────────────────────────────────────────┤
│ 同步层 (可选)                                              │
│  WebDAV 客户端：用户词库/配置的增量上传下载、冲突合并         │
└─────────────────────────────────────────────────────────┘
```

**关键点：引擎抽象层是本次架构的核心。** 因为「用 librime 还是自研」尚未定，抽象层让 M0 基准的结论可以无缝替换实现，而不改 UI/Service。

---

## 3. 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 语言 | Kotlin（UI/业务）+ C++17（引擎） | 用户已定 |
| 构建 | Gradle + AGP + CMake + NDK 28 | 与 trime 一致的可用组合 |
| 引擎 | 待基准决定：librime (静态链接) / 自研 | M0 基准 |
| 原生构建参考 | `osfans/trime` v3.3.12 的 `app/src/main/jni` | 已验证可在 Android 上构建 librime + OpenCC + snappy + 插件 |
| 引擎运行时 | `-DANDROID_STL=c++_static` | trime 同款，避免 STL 冲突 |
| 词库格式 | 主词库：自有二进制索引（mmap）；导入源：RIME yaml/txt | 启动快、内存可控 |
| 用户词库 | SQLite（Room）或纯文件，后续定 | 便于同步与冲突合并 |
| 同步 | WebDAV（`dav4jvm` 或自写 PUT/GET + PROPFIND） | 用户已定 |
| 配置 | DataStore / Room | 轻量 |
| CI/CD | GitHub Actions，构建 arm64-v8a APK → Release | 用户已定 |

---

## 4. 关键设计决策

### 4.1 引擎策略：基准驱动（M0）
用户担心 librime「初始化慢、内存大、按键延迟」。因此先做基准，量化下面指标，再决定：

- **冷启动/部署**：首次部署耗时、后续启动耗时
- **按键延迟**：P50 / P95 / P99（`SimulateKeySequence` + `GetContext`）
- **内存**：初始化后、部署后、连续输入后的 PSS
- **包体积**：`.so` 大小、词库大小
- **词典规模敏感性**：小词库 vs 大词库的退化曲线

判定规则（建议，M0 后校准）：
- 若 P95 按键延迟 < 30ms 且内存 < 80MB → **直接用 librime**，把精力放在词库/UX。
- 若延迟或内存不达标 → **复用 RIME 词库格式，自研轻量索引引擎**（只做前缀/首字母/音形码查询与词频排序）。

### 4.1.1 M0 实测结论（已完成，见第 10 节）

**采用 librime。** 延迟亚毫秒级、稳态内存 ~73MB，远优于阈值；唯一成本是首次部署（2.7s、峰值 ~240MB），可放到首次启动后台完成。

### 4.2 词库
- 主词库：导入 RIME 的 `*.dict.yaml`，编译为**自有只读二进制索引**（如 marisa-trie / double-array trie + 词频表），`mmap` 加载，避免每次解析 YAML。
- 小鹤音形：使用标准小鹤音形码表（纯正音形方案），作为独立 `schema`，与拼音共用同一引擎接口。
- 英文：内置小词库 + 用户学习，不追求专业纠错。

### 4.3 用户词库学习
- 自动记录上屏词 → 计入用户词频；支持一键清空 / 关闭学习。
- 只存本地（SQLite / 文本），结构要便于**按条目同步与冲突合并**（用「词 + 频次 + 更新时间戳」）。
- 自动调频影响候选排序权重，与主词库词频做加权合并。

### 4.4 同步（可选云）
- 协议：WebDAV（坚果云），只同步「用户词库 + 设置」这类小数据，**不同步主词库**。
- 策略：手动触发 + 可选自动；上传前本地去抖；冲突按时间戳合并（词频取较大者）。
- 默认关闭，首次使用需显式配置。

### 4.5 极简与省电
- 候选栏固定 1 行，最多 7 候选，不做花哨动画。
- 引擎懒加载：键盘弹出后再预热，避免拖慢系统。
- 后台无常驻线程、无定时器（同步是用户触发）。

---

## 5. 模块与目录（目标形态）

```
hexime/
├── PLAN.md                    # 本文档
├── README.md
├── benchmark/                 # M0：RIME 性能基准 demo（独立 App）
├── app/                       # 正式输入法 App（M1 起）
│   ├── src/main/java/com/hexime/ime/
│   │   ├── HeximeService.kt          # InputMethodService
│   │   ├── ui/                       # KeyboardView / CandidateBar
│   │   ├── engine/                   # InputEngine 抽象 + RimeEngine
│   │   └── data/                     # 词库、用户词、设置
│   └── src/main/cpp/                 # JNI 引擎
├── tools/
│   └── dictc/                        # 词库编译器：RIME yaml/txt → 二进制
├── scripts/
│   ├── prepare-native.sh             # 拉取/组装 librime 原生依赖
│   └── stage-rime-data.sh            # 下载 RIME 方案数据到 assets
└── .github/workflows/                # CI：编译 + Release
```

---

## 6. 里程碑

### M0 · RIME 性能基准 demo（当前）
产出：一个能安装的 benchmark APK，给出延迟/内存/部署耗时数据表。
- [x] 方案 + 骨架
- [ ] CI 打通：librime 静态编译 → APK → Release
- [ ] 真机跑：拼音（luna_pinyin）基准
- [ ] 加小鹤音形方案，复测
- [ ] 出一页结论：librime vs 自研

### M1 · 工程骨架 + 引擎抽象
- 正式 App 骨架、InputMethodService 可弹出、QWERTY 布局
- `InputEngine` 接口 + 空实现/桩
- CI 产出可安装 APK

### M2 · MVP 可用
- 拼音输入闭环（候选、上屏、退格、中英切换）
- 词库导入 + mmap 查询
- 极简候选栏

### M3 · 用户词库学习
- 自动学习/调频、开关、清空
- 用户词与主词库加权融合

### M4 · 小鹤音形
- 音形码表导入、方案切换、与拼音共存
- 纯英文输入完善

### M5 · 同步与打磨
- WebDAV 同步（手动 → 自动）
- 性能回归、省电、异常处理

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| librime Android 交叉编译复杂 | M0 卡住 | **复用 trime 的 jni 构建**（已验证），不自己从零写 CMake 依赖 |
| librime 性能不达标 | 需自研引擎 | M0 先量化；架构用 `InputEngine` 抽象隔离 |
| 小鹤音形方案版权/码表来源 | 无法分发 | 个人自用，本地导入；代码仓库不内置受版权码表 |
| 本机无法编译 | 迭代慢 | 全程 CI；本地只改代码 + 下载 APK |
| Boost 首次下载慢 | CI 超时 | CI 缓存 Boost/tar，或预置到 Release |
| GPL 传染 | 若开源需注意 | trime 的构建脚本为 GPL-3.0；自研代码注意许可边界（个人自用无碍） |

---

## 8. CI/CD

- 触发：`workflow_dispatch` + `push`（benchmark 相关路径）+ tag。
- 步骤：checkout → JDK17 → Android SDK/NDK/CMake → `prepare-native.sh` → `stage-rime-data.sh` → Gradle 构建 → 上传 artifact → tag 时发布 Release。
- 产物：`arm64-v8a` APK（用 debug key 签名，便于直接安装）。

---

## 9. 待定问题（后续再定）

1. 自研引擎若要做，trie 选型（marisa vs double-array vs DAWG）。
2. 用户词库存 SQLite 还是纯文本（影响同步合并复杂度）。
3. 小鹤音形码表的合法来源与更新方式。
4. 是否需要「简繁切换 / 模糊音」——当前未列入。

---

## 10. M0 实测结果（librime 基准）

**设备**：本机 Android 14 (API 34) / arm64-v8a
**版本**：librime 1.17.0，方案 `luna_pinyin` + `essay.txt`（预置词频）
**App**：`benchmark/`（debug key 签名的 release APK）

| 指标 | 结果 |
|---|---|
| `RimeInitialize` | **~1 ms**（惰性，不含部署） |
| 首次完整部署 | **2.7 s**，PSS 峰值 **~240 MB** |
| 已部署后再启动 (`fullCheck=false`) | **~0 ms**（no-op） |
| 按键延迟（每键，含 `GetContext`） | avg **0.03–0.23 ms**，p95 ≤ 0.65 ms，p99 ≤ 1.0 ms，max ≤ 1.9 ms |
| 稳态 PSS | **~73 MB**（含 Android 框架；librime 增量约 10–16 MB） |
| APK 体积 | ~4 MB（不含 RIME 数据）+ 数据 ~9.8 MB |

**候选质量**：
- 缺 `essay.txt` 时：`nihao → 㘈㘪 | 㘈 | …`（错，无词频权重）
- 补上后：`nihao → 你好 | 妳好 | …`，`zhongguo → 中國 | 種過 | …` ✅

### 结论
1. **键延迟完全不是问题**，librime 在 Android 上极快，无需自研引擎。
2. **一次性部署**是主要成本（2.7s / 240MB 峰值）。对策：首次安装后在后台部署，键盘不阻塞等待；避免运行时反复全量部署。
3. **必须捆绑 `rime-essay`**，否则候选排序错误（这是本次踩到的坑）。
4. **稳态内存 ~73MB** 可接受；后续可优化（懒加载方案、只部署需要的 schema）。
5. `luna_pinyin` 默认输出繁体；正式版应使用 `luna_pinyin_simp` 或加简繁开关。

### 仍未验证
- 小鹤音形方案的实际部署/查询性能（码表未内置，需本地导入后复测）。
- 插件（lua/octagram/predict）未并入 `rime-static`，当前基准不含；如方案需要再补。
