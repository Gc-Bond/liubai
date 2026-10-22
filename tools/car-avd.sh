#!/usr/bin/env bash
# 把护卫舰07(DiLink4.0)的运行环境复刻到本机模拟器,用于离车调试。
# 参数依据:carsnapshot/ 下的实机 dump(2026-07-27 现场采集)。
#
#   ./tools/car-avd.sh create   建 AVD(1920x1080 @240dpi / API 29 / 可 adb root)
#   ./tools/car-avd.sh start    启动(带 -writable-system,便于把车机 app 塞进 /system)
#   ./tools/car-avd.sh up       ★ 日常入口(幂等):起模拟器(已在跑就跳过)→ 等开机 → 拉起 Shizuku
#   ./tools/car-avd.sh shizuku  只补提权(模拟器已经在跑、但 Shizuku 被重启带走时)
#   ./tools/car-avd.sh apps     把 carsnapshot/apk 里的车机 app 装进模拟器
#   ./tools/car-avd.sh cutout   复刻车机顶部 90px 状态栏占用
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}"
AVD=Car_DiLink_API29
# 真车的无线调试(192.168.x.x:5555)常年挂在 adb devices 里(多半还是 offline),
# 裸 adb 在双 transport 下会报 "more than one device"。
# `adb -e` 也救不了:adb 把 ip:port 这种 TCP 设备一并算作 emulator,照样"more than one emulator"。
# 只能按 serial 前缀认,认到了就 export 给后续所有 adb 用。
use_emulator() {
  ANDROID_SERIAL=$(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/{print $1; exit}')
  [ -n "$ANDROID_SERIAL" ] || return 1
  export ANDROID_SERIAL
}
ADB=adb

# 按**界面上的文字**点一个元素。坐标从 uiautomator 现查,不写死 —— 写死的坐标
# 换个字号/换台机器就点空,而点空在这里是静默的(什么也不发生),最难查。
#
# 找到并点了返回 0,没找到返回 1。**调用方要把"没找到"当成一种正常结果**:
# 授权对话框只在没授权时才弹,已经授权过就该找不到。
ui_tap() {
  local txt="$1" node x1 y1 x2 y2
  $ADB shell uiautomator dump /sdcard/blank_ui.xml >/dev/null 2>&1 || return 1
  # 整份 dump 是一行,按 < 拆成一节点一行才能逐个 grep
  node=$($ADB shell cat /sdcard/blank_ui.xml 2>/dev/null | tr -d '\r' | tr '<' '\n' \
         | grep -F "text=\"$txt\"" | head -1) || true
  [ -n "$node" ] || return 1
  read -r x1 y1 x2 y2 <<EOF || true
$(printf '%s\n' "$node" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
EOF
  [ -n "${x2:-}" ] || return 1
  $ADB shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

# 界面上出现过这段文字没有。用来读探针页那块状态文本(它不是按钮,点不了,只能读)。
ui_has() {
  $ADB shell uiautomator dump /sdcard/blank_ui.xml >/dev/null 2>&1 || return 1
  $ADB shell cat /sdcard/blank_ui.xml 2>/dev/null | tr -d '\r' | grep -qF "$1"
}

# 装在模拟器上的留白是哪个包。debug 优先 —— 平时调的就是它。
blank_pkg() {
  $ADB shell pm list packages 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' \
    | grep -E '^com\.liubai\.blank(\.debug)?$' | sort -r | head -1
}

# google_apis 而非 google_apis_playstore:playstore 镜像禁用 adb root,
# 而提权链路(SelfAdbPrivileged/Shizuku)调试离不开 root。车机也没有 GMS。
IMG="system-images;android-29;google_apis;arm64-v8a"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "${1:-}" in
create)
  if ! "$SDK/cmdline-tools/latest/bin/sdkmanager" --list_installed 2>/dev/null | grep -q "android-29;google_apis;arm64"; then
    echo "→ 镜像未装,先下载(约 1.5G)..."
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --install "$IMG" "platforms;android-29"
  fi
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMG" --force
  CFG="$HOME/.android/avd/$AVD.avd/config.ini"
  # 实机:1920x1080,density 240(=1280x720dp 横屏),landscape
  cat >> "$CFG" <<'EOF'
hw.lcd.width=1920
hw.lcd.height=1080
hw.lcd.density=240
hw.initialOrientation=landscape
hw.keyboard=yes
hw.ramSize=4096
vm.heapSize=512
disk.dataPartition.size=8G
hw.gpu.enabled=yes
hw.gpu.mode=auto
showDeviceFrame=no
skin.dynamic=yes
skin.name=1920x1080
skin.path=_no_skin
EOF
  # 去重:后写的同名键会覆盖前面的,但清掉旧值更干净
  awk -F= '!/^$/{k=$1; v=substr($0,index($0,"=")+1); m[k]=v; if(!(k in seen)){seen[k]; order[++n]=k}} END{for(i=1;i<=n;i++) print order[i]"="m[order[i]]}' "$CFG" > "$CFG.tmp" && mv "$CFG.tmp" "$CFG"
  echo "✓ AVD 已建: $AVD"
  ;;

start)
  # -writable-system: 允许 remount /system,把车机系统 app 按系统 app 装进去
  # -no-snapshot-load: 避免快照把 remount 状态搞乱
  "$SDK/emulator/emulator" -avd "$AVD" -writable-system -no-snapshot-load -netdelay none -netspeed full "${@:2}"
  ;;

up)
  # 日常入口:起模拟器 + 等开机 + 拉提权,一条命令进到能调试的状态。
  # **幂等**:模拟器已经在跑就不再开一台,直接去补提权 —— 提权才是每次都会丢的那一半,
  # 而"忘了拉 Shizuku"正是空调/画中画/Dock 运行区集体失灵的原因,单跑这条也得能救回来。
  if use_emulator && [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    echo "→ 模拟器已在运行($ANDROID_SERIAL),跳过启动"
  else
    # start 是前台阻塞的,这里放后台再接着往下做。
    "$SDK/emulator/emulator" -avd "$AVD" -writable-system -no-snapshot-load \
        -netdelay none -netspeed full >/dev/null 2>&1 &
    echo "→ 模拟器启动中(冷启动,约 30~60 秒)..."
    # 不能用 adb wait-for-device:多 transport 下它同样报 more than one。轮询到 serial 出现为止。
    until use_emulator; do sleep 2; done
    until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
    echo "✓ 开机完成($ANDROID_SERIAL)"
  fi
  exec "${BASH_SOURCE[0]}" shizuku
  ;;

shizuku)
  # 为什么模拟器上必须走 Shizuku,而不是 app 内置的自连 adb:
  # SelfAdbPrivileged 连的是本机 127.0.0.1:5555,而模拟器的 adbd 走 qemu 管道、
  # 根本不监听 tcp(宿主那个 emulator-5554 是宿主端口,与 guest 内部无关)。
  # 想给它补上监听就得设 service.adb.tcp.port —— 一设 adbd 就切 TCP 模式、
  # 丢掉 qemu 管道,宿主当场失联,还会被关机快照记住,只能冷启动救回来(2026-07-29 实测踩过)。
  # 所以离车调提权只有 Shizuku 这一条路。真车上不受影响,那边 adbd 本来就开着 5555。
  #
  # Shizuku 服务端是 adb 拉起的普通进程,设备一重启就没,所以每次开机都得跑这一次。
  # 授权本身是持久化的(存在 Shizuku 自己的数据里),重拉服务不用再点一遍"允许"。
  use_emulator || { echo "! 没有在跑的模拟器,先 ./tools/car-avd.sh up"; exit 1; }
  PKG=moe.shizuku.privileged.api
  APK_LOCAL="$ROOT/tools/shizuku.apk"

  if ! $ADB shell pm path "$PKG" >/dev/null 2>&1; then
    if [ ! -f "$APK_LOCAL" ]; then
      echo "→ 未装 Shizuku,下载最新版..."
      URL=$(curl -sL --connect-timeout 15 https://api.github.com/repos/RikkaApps/Shizuku/releases/latest \
            | grep -o 'https://[^"]*-release\.apk' | head -1)
      [ -n "$URL" ] || { echo "! 拿不到下载地址,请手动下 Shizuku 放到 $APK_LOCAL"; exit 1; }
      curl -sL --connect-timeout 20 -o "$APK_LOCAL" "$URL"
    fi
    $ADB install -r "$APK_LOCAL" >/dev/null
    echo "✓ Shizuku 已安装"
  fi

  # so 跟着 apk 走,重装后路径会变(带 hash),每次现查
  APK=$($ADB shell pm path "$PKG" | sed 's/package://' | tr -d '\r')
  $ADB shell "$(dirname "$APK")/lib/arm64/libshizuku.so"

  # starter 退出码不可信(它 fork 出 server 就走),认进程在不在。
  # 要轮询:starter 打印完 pid 时进程还叫 app_process,`shizuku_server` 这个名字是
  # server 自己起来后改的,紧挨着查必然扑空(实测)。
  PID=""
  for _ in 1 2 3 4 5; do
    # `|| true` 不能省:pidof 没找着时返回 1,pipefail 把它带出管道,set -e 下这条赋值就把脚本弄退出了
    PID=$($ADB shell "pidof shizuku_server" 2>/dev/null | tr -d '\r') || true
    if [ -n "$PID" ]; then break; fi   # 别写成 `[ … ] && break`:set -e 下空值那次会直接把脚本弄退出
    sleep 1
  done
  if [ -z "$PID" ]; then
    echo "! Shizuku 服务没起来 —— 提权全链路(空调/画中画/Dock 运行区)会静默降级"
    exit 1
  fi
  echo "✓ Shizuku 服务已拉起(pid $PID)"

  # ---- 授权:脚本自己点,不再靠人记 ----
  #
  # Shizuku 服务端有自己的授权表,和 Android 权限是两回事(所以 `pm grant … API_V23`
  # 代替不了它:只改 Android 权限,ready() 照样 false,还留下 granted=true 的假象,
  # 2026-08-14 实测踩过)。这张表按 uid 记,**重装 app 就丢** —— server 重启倒是不用重点。
  #
  # 这里原来只打印一段"去 Shizuku 里点一下"的提示,于是每次重装 debug 包都栽同一个坑:
  # ready()=false → 空调一片 "--"、Dock 运行区点了没反应,看着像功能坏了(2026-08-16 又栽一次,
  # 当时 Shizuku 界面上是 Authorized 0 applications)。提示治不了这个,得脚本自己走一遍。
  #
  # 走的是探针页那个「Shizuku授权」按钮(exported,adb 拉得起),不是去 Shizuku 的设置里翻:
  # 授权对话框只有 app 主动 requestPermission 才弹,Shizuku 那边的 Authorized applications
  # 列表只列**已授权**的,没授权的根本不在里面,点不到。
  #
  # 幂等:已经授权过就不弹对话框,ui_tap 找不到按钮、跳过,不会重复点。
  BLANK=$(blank_pkg) || true
  if [ -z "$BLANK" ]; then
    echo "→ 模拟器上还没装留白,授权这步跳过(装完再跑一次 ./tools/car-avd.sh shizuku)"
  else
    # -S(先杀再起)不能省:留白已经在跑时,直接 am start 探针页会被
    # "delivered to currently running top-most instance" 吞掉,前台还是桌面,
    # 后面 ui_tap 就点空了(实测)。顺带也让新进程重新握一次 Shizuku binder。
    $ADB shell am start -S -n "$BLANK/com.liubai.blank.PrivilegeProbeActivity" >/dev/null 2>&1 || true
    sleep 3
    if ui_tap "Shizuku授权"; then
      sleep 2
      # 系统语言跟着镜像走,中英文各试一次
      if ui_tap "Allow all the time" || ui_tap "一律允许"; then
        echo "✓ 已替你点掉 Shizuku 授权($BLANK)"
      else
        echo "→ 没弹授权框:$BLANK 之前已经授权过"
      fi
    else
      echo "! 探针页没起来,授权没点成 —— 手点:留白 → PrivilegeProbeActivity → Shizuku授权"
    fi

    # ---- 自检:认 app 当场算出来的那句话 ----
    #
    # **别拿 policy_control 当判据**:它是全局持久设置,上一轮成功写进去的值会一直留着,
    # 这一轮提权就算是断的也照样读得到 —— 假阳性(2026-08-16 差点被它骗过去)。
    # 探针页这块状态是点一下现算的:pingBinder + checkSelfPermission + 真跑一条 id 命令,
    # Shizuku 授权、Shell 通道、身份是不是 uid 2000,一次全验掉。
    ui_tap "检测提权" >/dev/null 2>&1 || true
    sleep 2
    if ui_has "READY"; then
      echo "✓ 提权已就绪(探针页:Shizuku READY,身份 uid 2000)"
    else
      echo "! 提权仍没就绪 —— 空调会一片 \"--\"、画中画起不来、Dock 运行区点了没反应"
      echo "  探针页还开着,点「检测提权」看是卡在哪一步"
    fi

    $ADB shell am start -n "$BLANK/com.liubai.blank.HomeActivity" >/dev/null 2>&1 || true
  fi

  # ---- 另一个每次都栽的环境假象,跟提权无关 ----
  echo
  echo "  ★ 「Dock 图标点不动」的判据(2026-08-16 实测,别再当成模拟器假象):"
  echo "    无障碍在线时 Dock/顶栏挂的是 2032(ACCESSIBILITY_OVERLAY),层级压过系统栏,"
  echo "    整条都点得动;无障碍掉线才退到 2038(121000),被系统导航栏(231000,"
  echo "    [0,1008][1920,1080])吃掉下 72px —— 图标中心 y≈1032 正在里面,只剩上沿 24px。"
  echo "    所以点不动=提权/无障碍那条链断了,先看上面这行自检,别去改 hw.mainKeys。"
  echo "    临时验证可以打上沿:adb shell input tap <x> 1000"
  ;;

apps)
  # 车机 app 装进模拟器:只为让桌面有真实的包名/图标/可启动目标。
  # /system/priv-app 里的 app 多数请求 signature|privileged 权限,以普通 app 装会
  # 缺权限甚至启动即崩 —— 但对桌面开发无所谓:能被 PackageManager 列出、
  # 有图标、startActivity 不报 ActivityNotFound 就够了。
  use_emulator || { echo "! 没有在跑的模拟器,先 ./tools/car-avd.sh up"; exit 1; }
  ok=0; fail=0
  : > "$ROOT/carsnapshot/install_failed.txt"
  for f in "$ROOT"/carsnapshot/apk/*.apk; do
    if $ADB install -r -d --no-streaming "$f" >/dev/null 2>&1; then
      ok=$((ok+1)); printf '.'
    else
      fail=$((fail+1)); printf 'x'; basename "$f" >> "$ROOT/carsnapshot/install_failed.txt"
    fi
  done
  echo; echo "✓ 装上 $ok 个,失败 $fail 个(清单见 carsnapshot/install_failed.txt)"
  ;;

cutout)
  # 实机 DisplayCutout: insets Rect(0,90-0,0),开孔 Rect(932,0-988,90) —— 1920 正中,
  # 屏幕上那颗语音球就压在这。用居中刘海近似复刻顶部 90px 的不可用区。
  use_emulator || { echo "! 没有在跑的模拟器,先 ./tools/car-avd.sh up"; exit 1; }
  $ADB shell cmd overlay enable com.android.internal.display.cutout.emulation.corner 2>/dev/null \
    || echo "! 该镜像无 cutout overlay,可在 开发者选项 → 模拟刘海屏 手动开"
  echo "参考实机: 状态栏 90px / app 可用区 1920x990 / 底部另有车机导航栏"
  ;;

*)
  sed -n '2,10p' "${BASH_SOURCE[0]}"; exit 1;;
esac
