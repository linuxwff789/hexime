#!/data/data/com.termux/files/usr/bin/bash
# hexime-backup.sh — 备份 hexime 输入法的用户数据（加词词表 + 设置）→ GitHub 私有仓库
#
#   用法: hexime-backup.sh [--dry-run] [--restore] [--quiet]
#     (无参数)   备份并推送到 linuxwff789/hexime-backup
#     --restore  从仓库取回，写回手机（会先 force-stop 输入法）
#
# 设计:
#   * 长期 git 工作树 $HOME/.backups/hexime，增量 commit（改了什么推什么）
#   * 读 app 私有数据用 /data_mirror/data_ce/null/0/<pkg>/（A14 上 /data/data/<pkg> 连 su 都进不去）
#   * 恢复用 `cat > 目标文件`（保留原 inode/属主/SELinux 标签），不用 cp 覆盖
#   * cron no_agent 模式: stdout 只在异常时说话, 日常静默; 日志走文件
set -euo pipefail

REPO="linuxwff789/hexime-backup"
PKG="com.hexime.ime"
APP="/data_mirror/data_ce/null/0/$PKG"
WORK="$HOME/.backups/hexime"
DRY=0; RESTORE=0; QUIET=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY=1 ;;
    --restore) RESTORE=1 ;;
    --quiet)   QUIET=1 ;;
  esac
done

LOGDIR="$HOME/.hermes/cron"; mkdir -p "$LOGDIR"
LOGF="$LOGDIR/hexime-backup.log"
log() { local m="[$(date '+%F %T')] $*"; echo "$m" >> "$LOGF"; { [ $QUIET -eq 0 ] || [ -t 1 ]; } && echo "$m" >&2 || true; return 0; }
fail() { log "FAIL: $*"; echo "hexime-backup 失败: $*" >&2; exit 1; }
trap 'fail "意外退出 line $LINENO"' ERR

# 需要 root 读 app 私有目录
command -v su >/dev/null || fail "没有 su"
su -c true 2>/dev/null || fail "su 不可用"

# ---------- 恢复 ----------
if [ "$RESTORE" = 1 ]; then
  [ -d "$WORK/.git" ] || fail "还没有备份工作树 $WORK（先跑一次备份）"
  git -C "$WORK" fetch -q origin main && git -C "$WORK" reset -q --hard origin/main
  log "force-stop $PKG（否则内存里的 prefs 会把写回的设置整份覆盖掉）"
  su -c "am force-stop $PKG" || true
  sleep 1

  # app 的属主：恢复出来的文件必须是它的 —— 用 root 写出来的文件是 root:root，
  # app(uid 10389) 读它直接 Permission denied，恢复等于没恢复（实测过）。
  APP_UID="$(su -c "stat -c %u $APP")"
  [ -n "$APP_UID" ] || fail "拿不到 $APP 的 uid"
  # app 数据目录的 SELinux 标签（带类别，如 app_data_file:s0:c133,c257,c512,c768）。
  # 用 root 新建的文件默认是 app_data_file:s0（没类别）——app 自己读不到，必须照抄这个标签。
  APP_CTX="$(su -c "ls -Zd $APP" | awk '{print $1}')"
  [ -n "$APP_CTX" ] || fail "拿不到 $APP 的 SELinux 标签"

  # 属主/标签一律修成当前 app 的：**重装后 uid 会变**（10389 → 10390 …），旧目录还属于
  # 上一个 uid，app 连子目录都建不了 → files/rime/shared 建不出来 → copyAssets 抛
  # FileNotFoundException → 后台线程没人接 → 一打开就闪退（实测踩过）。
  # 所以不是"新建时才修"，而是每次恢复都把要用的目录整棵修一遍。
  fix_owner() {
    local p="$1"
    [ -e "$p" ] || return 0
    # 不吞错误：恢复出 app 读不到的文件比直接失败更糟
    su -c "chown -R $APP_UID:$APP_UID '$p' && chcon -R '$APP_CTX' '$p'" \
      || fail "修属主/标签失败: $p"
  }

  # 目标目录可能还不存在（刚装完、app 还没写过 prefs）。
  # 注意：mkdir -p 是 root 建的，中间层（files/rime）也会是 root:root，
  # 所以属主/标签统一放到最后整棵树修（见下面 fix_owner 调用）。
  ensure_dir() {
    local d="$1"
    [ -d "$d" ] && return 0
    su -c "mkdir -p '$d'" || fail "建目录失败: $d"
    log "新建目录 $d"
  }

  # cat > 保留原 inode/属主（已存在时）；新建的文件要补属主 + 标签。
  restore_file() {
    local src="$1" dst="$2" dir
    dir="$(dirname "$dst")"
    ensure_dir "$dir"
    su -c "cat > '$dst'" < "$src" || fail "写 $dst 失败"
    log "已恢复 $(basename "$dst")（$(wc -c < "$src" | tr -d ' ') 字节，属主 $APP_UID）"
  }

  if [ -f "$WORK/custom_phrase.txt" ]; then
    restore_file "$WORK/custom_phrase.txt" "$APP/files/rime/user/custom_phrase.txt"
    log "  词条数 $(grep -vc '^#' "$WORK/custom_phrase.txt" || true)"
  fi
  if [ -f "$WORK/hexime_settings.xml" ]; then
    restore_file "$WORK/hexime_settings.xml" "$APP/shared_prefs/hexime_settings.xml"
  fi
  # 最后统一把整棵树修成当前 app 的属主/标签：
  #  * 重装后 uid 会变（10389 → 10391 …），旧目录还属于上一个 uid；
  #  * mkdir -p 建的中间层目录是 root:root。
  # 不修的话 app 建不了 files/rime/shared → 引擎初始化失败（甚至后台线程抛异常闪退）。
  log "修属主/标签（uid $APP_UID）"
  fix_owner "$APP/files"
  fix_owner "$APP/shared_prefs"

  log "恢复完成 —— 打开输入法会重新部署词库"
  exit 0
fi

# ---------- 备份 ----------
mkdir -p "$WORK"
if [ ! -d "$WORK/.git" ]; then
  log "初始化工作树 $WORK"
  git init -q -b main "$WORK"
  git -C "$WORK" remote add origin "https://github.com/$REPO.git"
  git -C "$WORK" config user.name  "hexime-backup"
  git -C "$WORK" config user.email "hexime-backup@localhost"
  git -C "$WORK" fetch -q origin main 2>/dev/null \
    && git -C "$WORK" reset -q --hard origin/main || true
fi

# 用户词表（加词功能写的）
if su -c "test -f $APP/files/rime/user/custom_phrase.txt"; then
  su -c "cat $APP/files/rime/user/custom_phrase.txt" > "$WORK/custom_phrase.txt"
  log "custom_phrase.txt: $(grep -vc '^#' "$WORK/custom_phrase.txt" || true) 条"
else
  log "custom_phrase.txt 还不存在（还没加过词）"
  : > "$WORK/custom_phrase.txt"
fi

# 设置（配色/键盘高度/震动/跟手延迟）
if su -c "test -f $APP/shared_prefs/hexime_settings.xml"; then
  su -c "cat $APP/shared_prefs/hexime_settings.xml" > "$WORK/hexime_settings.xml"
  log "hexime_settings.xml 已取回"
fi

cat > "$WORK/README.md" <<'EOF'
# hexime-backup

hexime 输入法的备份（自动推送）。

- `custom_phrase.txt` — 加词功能写进用户词表的词条（`词<TAB>码`）
- `hexime_settings.xml` — 配色 / 键盘高度 / 震动 / 跟手延迟等设置

恢复：`bash tools/hexime-backup.sh --restore`（在装了 hexime 的手机上跑，需要 root）。
EOF

if [ "$DRY" = 1 ]; then
  log "dry-run：工作树状态"
  git -C "$WORK" status --short
  exit 0
fi

git -C "$WORK" add -A
if git -C "$WORK" diff --cached --quiet; then
  log "没有变化，跳过"
  exit 0
fi
git -C "$WORK" commit -q -m "backup $(date '+%F %T')"
git -C "$WORK" push -q origin main
log "已推送 $(git -C "$WORK" rev-parse --short HEAD)"
