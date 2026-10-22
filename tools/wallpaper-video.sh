#!/usr/bin/env bash
# 把视频素材转成车机壁纸能长时间稳跑的规格,放进 app/src/main/assets/wallpapers/。
#
#   ./tools/wallpaper-video.sh check              看现有素材的规格,标出超标的
#   ./tools/wallpaper-video.sh 某视频.mp4 ...      转码并放进 assets/wallpapers/
#   ./tools/wallpaper-video.sh all                把 assets/wallpapers/ 里现有的全转一遍
#
# 为什么要转:壁纸是**开着桌面就一直在解**的东西,不是点开看一下的片子。原素材
# (2560×1440 / 60fps / 9.96Mbps)在模拟器上跑 3 秒就把解码器压崩了 ——
#   W CCodec  : previous call to queue exceeded timeout
#   E MediaCodec: Codec reported err 0x80000000
# 画面随即冻住。真车是硬解、余量大得多,但桌面还要同时喂两路画中画虚拟屏,
# 没必要拿壁纸去挤那点解码带宽。
#
# 转出来的规格(可用环境变量覆盖):
#   1920×1080   车机屏就这么大,再高的像素一个都显示不出来
#   30fps       壁纸不是游戏,30 帧的云和水看不出比 60 帧差
#   CRF 23      质量恒定,简单画面自动省体积;maxrate 兜住复杂画面的峰值
#   无音轨      壁纸本来就静音播(见 HomeActivity.openVideoWallpaper),留着音轨等于
#               白占一路 aac 解码器实例 —— logcat 里那个 c2.android.aac.decoder 就是它
#   faststart   moov 前置,起播不用先把整个文件读一遍
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/wallpapers"
# 原素材的去处:转码是就地替换,原件不能留在 assets 里(会被整个打进 APK)
SRC_KEEP="$ROOT/wallpaper-src"

# 封面文件的后缀。跟 WallpaperStore.COVER_SUFFIX 必须一致 —— 改这里就要改那里。
COVER=".cover.jpg"

W="${WP_WIDTH:-1920}"
H="${WP_HEIGHT:-1080}"
FPS="${WP_FPS:-30}"
CRF="${WP_CRF:-23}"
MAXRATE="${WP_MAXRATE:-4M}"

command -v ffmpeg >/dev/null || { echo "缺 ffmpeg:brew install ffmpeg"; exit 1; }

# 一行规格摘要:分辨率 / 帧率 / 码率 / 体积
probe() {
  local f="$1"
  ffprobe -v error -select_streams v:0 \
    -show_entries stream=width,height,r_frame_rate \
    -show_entries format=duration,bit_rate,size \
    -of default=noprint_wrappers=1:nokey=0 "$f" 2>/dev/null | awk -F= '
      /^width=/   {w=$2}
      /^height=/  {h=$2}
      /^r_frame_rate=/ {split($2,a,"/"); fps=(a[2]>0)? a[1]/a[2] : 0}
      /^duration=/{d=$2}
      /^bit_rate=/{br=$2}
      /^size=/    {sz=$2}
      END {printf "%dx%d  %.0ffps  %.1fMbps  %.1fs  %.1fMB", w, h, fps, br/1000000, d, sz/1048576}'
}

# 有音轨吗。壁纸不需要,转码时会去掉,check 时提一句
has_audio() {
  [ -n "$(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$1" 2>/dev/null)" ]
}

transcode() {
  local in="$1" name out tmp
  name="$(basename "$in")"
  out="$DEST/${name%.*}.mp4"
  tmp="$(mktemp -t wp).mp4"

  echo "  转码前: $(probe "$in")$(has_audio "$in" && echo "  (带音轨)")"

  # scale + crop:先按较大的那边放大盖满,再裁掉溢出 —— 和 app 侧 centerCrop 同一套规矩,
  # 裁掉的正是屏幕上本来就看不见的部分,所以这里裁完,运行时就不用再裁
  #
  # **两头的 sar 处理不能少**(「大树下的阳光」就栽在这上面:原片 3840×2160 却带着
  # SAR 540:599,真实显示比是 1.602 而不是 16:9):
  #   开头 scale=iw*sar:ih —— 先把非方形像素摊成方形,否则后面的 scale/crop 按**编码尺寸**
  #     算,裁出来的构图和这片子实际该有的样子对不上;
  #   结尾 setsar=1 —— 把 SAR 钉成 1:1 再输出。不钉的话它会**原样传给成品**,于是
  #     播放器按 SAR 校正后显示、而封面 jpg 落地时 SAR 信息直接丢掉,同一张壁纸
  #     两条通路差出百分之十几的构图 —— 表现就是垫底图切到视频那一刻画面"跳一下"。
  # SAR 本来就是 1:1 的素材过这两步是空操作(iw*1=iw),所以不必分情况。
  ffmpeg -y -loglevel error -i "$in" \
    -an \
    -vf "scale=iw*sar:ih,scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},setsar=1,fps=${FPS}" \
    -c:v libx264 -profile:v high -level 4.0 -preset slow \
    -crf "$CRF" -maxrate "$MAXRATE" -bufsize "$(( ${MAXRATE%M} * 2 ))M" \
    -g "$(( FPS * 2 ))" -pix_fmt yuv420p -movflags +faststart \
    "$tmp"

  # 顺手生成封面。列表和主题卡要显示视频的第一帧,运行期用 MediaMetadataRetriever 抽的话
  # 每张都要占一路解码器 —— 而桌面上的视频壁纸本来就占着一路,一起抢就会吐出半解码的
  # 绿色条纹帧(实测主题卡预览稳定坏掉)。构建期抽好存成 jpg,运行期当普通图片解,零视频解码。
  # 960 宽:视频壁纸重开时的垫底图也用它,拉到 1920 只放大一倍,不糊。
  ffmpeg -y -loglevel error -i "$tmp" -vframes 1 -vf "scale=iw*sar:ih,scale=960:-2" -q:v 3 "$DEST/${name%.*}.mp4$COVER" 2>/dev/null || true

  # 原件先挪走再落新文件:同名 mov→mp4 的情况下,不挪的话新文件会把原件盖掉
  mkdir -p "$SRC_KEEP"
  if [ "$(cd "$(dirname "$in")" && pwd)" = "$DEST" ]; then
    mv "$in" "$SRC_KEEP/$name"
    echo "  原件已移出 assets → wallpaper-src/$name"
  fi
  mv "$tmp" "$out"
  echo "  转码后: $(probe "$out")"
  echo "  ✓ $out"
}

case "${1:-}" in
covers)
  # 只补封面,不碰视频本身。给"已经压好、不该再转一遍"的素材用 —— 重转就是二次压缩掉画质。
  shopt -s nullglob
  for f in "$DEST"/*.mp4 "$DEST"/*.webm "$DEST"/*.mkv "$DEST"/*.3gp; do
    out="$f$COVER"
    ffmpeg -y -loglevel error -i "$f" -vframes 1 -vf "scale=iw*sar:ih,scale=960:-2" -q:v 3 "$out"
    echo "  ✓ $(basename "$out")  ($(du -h "$out" | cut -f1))"
  done
  ;;
check)
  shopt -s nullglob
  found=0
  for f in "$DEST"/*.mp4 "$DEST"/*.webm "$DEST"/*.mkv "$DEST"/*.mov "$DEST"/*.3gp; do
    found=1
    echo "$(basename "$f")"
    echo "  $(probe "$f")$(has_audio "$f" && echo "  ⚠ 带音轨,白占一路解码器")"
    # 超标提示:像素比屏幕还多、帧率高于 30、码率高于 5Mbps —— 三条各自都够拖垮长时间解码
    ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate \
      -show_entries format=bit_rate -of default=noprint_wrappers=1:nokey=1 "$f" 2>/dev/null | \
      awk -v n="$(basename "$f")" 'NR==1{w=$1} NR==2{h=$1} NR==3{split($1,a,"/"); fps=(a[2]>0)?a[1]/a[2]:0} NR==4{br=$1}
        END {
          if (w*h > 1920*1080 || fps > 31 || br > 5000000)
            print "  ⚠ 超出车机壁纸建议规格,跑 ./tools/wallpaper-video.sh all 转一遍"
          else print "  ✓ 规格合适"
        }'
    # 像素方不方。非 1:1 的 SAR 从规格摘要上**完全看不出来**(宽高照样报 1920×1080),
    # 但播放器会按它校正显示、封面 jpg 落地时又把它丢掉,同一张壁纸两条通路差出构图 ——
    # 表现是垫底图切到视频那一刻"跳一下"。重转一遍即可(转码链里有 setsar=1)。
    sar="$(ffprobe -v error -select_streams v:0 -show_entries stream=sample_aspect_ratio -of csv=p=0 "$f" 2>/dev/null)"
    case "$sar" in
      "" | "1:1" | "N/A" | "0:1") ;;
      *) echo "  ⚠ 像素不是方的(SAR=$sar):垫底图和视频构图会对不上,跑 ./tools/wallpaper-video.sh 重转" ;;
    esac
  done
  [ "$found" = 1 ] || echo "assets/wallpapers/ 里没有视频素材"
  ;;
all)
  shopt -s nullglob
  found=0
  for f in "$DEST"/*.mp4 "$DEST"/*.webm "$DEST"/*.mkv "$DEST"/*.mov "$DEST"/*.3gp; do
    found=1
    echo "$(basename "$f")"
    transcode "$f"
  done
  [ "$found" = 1 ] || echo "assets/wallpapers/ 里没有视频素材"
  ;;
"" | -h | --help)
  sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  ;;
*)
  mkdir -p "$DEST"
  for f in "$@"; do
    [ -f "$f" ] || { echo "找不到 $f"; exit 1; }
    echo "$(basename "$f")"
    transcode "$f"
  done
  ;;
esac
