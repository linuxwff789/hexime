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
  if [ -f "$WORK/custom_phrase.txt" ]; then
    su -c "mkdir -p $APP/files/rime/user && cat > $APP/files/rime/user/custom_phrase.txt" < "$WORK/custom_phrase.txt"
    log "已恢复 custom_phrase.txt（$(grep -vc '^#' "$WORK/custom_phrase.txt" || true) 条）"
  fi
  if [ -f "$WORK/hexime_settings.xml" ]; then
    su -c "cat > $APP/shared_prefs/hexime_settings.xml" < "$WORK/hexime_settings.xml"
    log "已恢复设置"
  fi
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
