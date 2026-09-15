# hexime

个人自用的 Android 输入法：中文拼音 + 小鹤音形 + 纯英文，本地优先、极简、低延迟。

- 完整方案见 [PLAN.md](./PLAN.md)
- 当前阶段：**M0 — RIME 性能基准 demo**（见 [benchmark/](./benchmark)）

## 为什么先做基准

引擎候选方案是「直接用 librime」还是「复用 RIME 词库 + 自研轻量引擎」，
取决于 librime 在 Android 上的真实延迟/内存表现。benchmark 用数据说话。

## 目录

- `benchmark/` — 独立的基准 App（不影响正式 App）
- `scripts/` — 原生依赖组装、RIME 数据下载
- `.github/workflows/` — CI，编译 APK 并发布到 Release
