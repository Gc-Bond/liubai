#!/usr/bin/env bash
# 守在这儿等车机开机,一连上就把日志抢下来。
#
#   ./tools/car-boot-log.sh              # 守候并落盘到 logs/boot-<时间>.log
#   ./tools/car-boot-log.sh 全部          # 不过滤,连系统日志一起存(排查绑定时序要这个)
#
# 为什么需要它:车机 main ring buffer 只有 256 KiB,**实测只能保 54 秒**。
# 开机后手动去连、再 `logcat -d`,启动链那段早被挤没了 —— 2026-08-24 连着两次
# 想抓无障碍开机绑定失败的现场,都是这么错过的(日志空会被误读成"代码没跑")。
#
# 所以这脚本做三件事,顺序要紧:
#   ① 轮询等设备(车机重启时 adb 会 offline,要 disconnect 再 connect 才认)
#   ② 一连上**立刻** logcat -G 8M —— 先扩容再抓,不然边抓边丢
#   ③ 先 `-d` 捞一次已经在 buffer 里的,再挂持续流;断线自动回到 ①
#
# `persist.logd.size` 那条路走不通:shell 身份被 SELinux 挡,setprop 直接失败,
# 所以扩容只能在每次连上之后做一遍(重启就回 256 KiB)。
set -uo pipefail

DEV="${CAR_ADB:-192.168.43.10:5555}"
MODE="${1:-留白}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/boot-$(date '+%m%d-%H%M%S').log"

# 只看留白自己的 + 无障碍绑定,别的滤掉。排查绑定时序时用「全部」,
# 因为 AccessibilityManagerService 的报错不带 Blank 前缀。
FILTER='Blank/|Accessibility|dy_accessibility'

echo "守候中 → $DEV"
echo "落盘到 → $OUT"
[ "$MODE" = "全部" ] && echo "模式   → 全部(不过滤)" || echo "模式   → 只留白+无障碍(要全部就跑 $0 全部)"
echo "按 Ctrl-C 停。车机现在可以重启了。"
echo

connected() {
  adb devices | grep -q "^${DEV}[[:space:]]\+device$"
}

while true; do
  # ① 等设备。offline 是车机重启后的常态,必须 disconnect 一次才能重新握手
  until connected; do
    adb disconnect "$DEV" >/dev/null 2>&1
    adb connect "$DEV" >/dev/null 2>&1
    connected || sleep 2
  done

  echo "[$(date '+%H:%M:%S')] 已连上,扩容 buffer 并开始抓"
  # ② 先扩容。这一步失败也继续 —— 有 256 KiB 总比什么都没有强
  adb -s "$DEV" shell "logcat -G 8M" >/dev/null 2>&1 || echo "  (扩容失败,仍按 256 KiB 抓)"

  # ③ 先捞 buffer 里已有的,再挂持续流。两段都进同一个文件
  {
    echo "===== [$(date '+%H:%M:%S')] 连上后先捞一次 buffer 里已有的 ====="
    adb -s "$DEV" shell "logcat -b all -d -v time" 2>&1
    echo "===== [$(date '+%H:%M:%S')] 以下是持续流 ====="
    adb -s "$DEV" shell "logcat -b all -v time" 2>&1
  } | if [ "$MODE" = "全部" ]; then cat; else grep --line-buffered -E "$FILTER|^====="; fi >> "$OUT"

  # 走到这儿说明流断了(车机重启 / WiFi 掉),回去重连
  echo "[$(date '+%H:%M:%S')] 连接断开,继续守候"
  sleep 2
done
