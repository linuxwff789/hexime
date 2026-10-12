# hexime

自用的 Android 输入法：**小鹤音形**为主，兼有朙月拼音 / 简体 / 全拼 / 笔画，英文直通。
引擎直接用 librime（JNI 静态链接 rime-lua / octagram / predict），键盘与 12 套配色从 trime2 的「小鹤」主题整体搬过来。
本地优先、低延迟、不联网（除了你自己配的 GitHub 备份）。

**当前版本：1.0** · 最低 Android 10（API 29）

## 安装

签名已固定（keystore 存私有备份仓库），可以直接覆盖升级，不会丢词表和设置：

```
pm install -r hexime-release.apk
ime enable com.hexime.ime/.HeximeService
appops set com.hexime.ime SYSTEM_ALERT_WINDOW allow    # 加词浮窗要用
```

刻意**不做** `ime set`：装完不会把你正在用的输入法切走，自己到系统里切。

APK 见 [Releases](https://github.com/linuxwff789/hexime/releases)。

## 功能

- **键盘**：trime2「小鹤」主题的 68 个键盘，用 `tools/kbconvert.lua` 从 lua 转成 JSON，Kotlin 只做 dispatch。
  选单 / 菜单2 的键面是主题里的单色 PNG，按当前配色 SRC_IN 染色，中文小字在下 —— 和 trime2 一样。
- **反查**：输入全码再打一个反引号（助记键盘上反引号 = 长按 z）。
  正常码表只收「全码首选」，一些字的全码是隐藏的（如 好=hcn、天=tma、人=rfp），平时打不出来，反查就是给它们用的。
- **加词**：空格上滑，或选单「加词」。词条落 `custom_phrase.txt`（方案里的 `custom_phrase` 走 stabledb 直读），重启引擎即生效。
- **配色**：12 套（孤寺 / 暮暮 / 暗堂等从主题 lua 里抽出来），选单或设置页里选，即时生效。
- **方案**：openfly 小鹤音形 / rime_ice 雾凇拼音（含英文 melt_eng、部件拆字 radical_pinyin 两个依赖方案）。
- **备份同步**：`custom_phrase.txt` + 输入法设置 → 自己的私有 GitHub 仓库，Termux 侧 cron 与 App 内各一条路。
  **本地优先**：云端只在本机「还没拉过」时（首次安装 / 换机）自动拉，之后要主动用云端覆盖本地就点设置页的「下载」。
- **性能**：候选栏 TextView 复用池、按键命中按行分桶、JNI 一次调用取回整屏状态、小鹤码表压成紧凑数组（约 2MB）。
  实测 Native Heap ~17MB / Dalvik ~4MB；「跟手延迟」显示默认关。

## 构建

CI：push 到 main（改动 `ime/**` 或 `scripts/**`）触发 [Build IME](.github/workflows/build-ime.yml)，
约 5 分钟，成功后发 Release（tag `ime-<run_number>`）；推 `v1.0` 这种 tag 则发对应版本号的 Release。

本地：

```
bash scripts/prepare-native.sh ime/src/main/cpp            # 组装 librime + Boost/OpenCC/leveldb/marisa
bash scripts/stage-rime-data.sh ime/src/main/assets/rime   # 拉词库并生成 openfly schema
lua5.4 tools/kbconvert.lua                                 # 改了 trime2 键盘后重转
```

## 已知缺失（相对 trime2 的小鹤主题）

| trime2 有 | hexime 现状 |
|---|---|
| 语音输入 | 无（该键在转换时已按 `SKIP_ICON` 去掉，不留死键） |
| 提示 / 注解 / 候选窗 开关 | 无实现，同样已从菜单2 去掉 |
| `editor.lua` / `number_default.lua`（flex_box 布局） | 没转 |
| web_search 脚本键 | 无 |
| 简繁 | 小鹤方案下无变化（openfly 没声明 `simplification`）；雾凇拼音下正常 |

## 目录

- `ime/` — 正式 App（Kotlin + C++ JNI + assets）
- `benchmark/` — 独立的 librime 性能基准 App（不影响正式 App）
- `tools/` — `kbconvert.lua`（trime2 键盘 → JSON）、`gen_char_code.py`（小鹤码表）、`extract_palettes.py`（配色）、`hexime-backup.sh`（Termux 侧备份）
- `scripts/` — 原生依赖组装、RIME 数据下载
- `.github/workflows/` — CI

## 文档

- [PLAN.md](./PLAN.md) — 完整方案与取舍记录
