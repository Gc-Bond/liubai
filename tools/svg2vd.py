#!/usr/bin/env python3
"""按 tools/icon_map.json 把 BYD 原厂 SVG 转成 res/drawable/ic_*.xml。

换图标不要手改 ic_*.xml —— 改 icon_map.json 再跑这个脚本。

路径数据原样带过来，靠 <group scaleX/Y> 把原始 viewBox 缩到 24 网格：
既满足网格规格，又不用重算一个坐标（重算坐标是弧线参数出错的常见来源）。
颜色一律填 #FFFFFFFF，运行时靠 ModeApplier 的 tint 覆盖。

用法: python3 tools/svg2vd.py [--dry]
"""
import json, os, re, sys, math

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC  = os.path.join(ROOT, "tools/icons_src")   # 源 SVG 随 repo 走，产线不依赖项目外的目录
DST  = os.path.join(ROOT, "app/src/main/res/drawable")
GRID, ACTIVE = 24.0, 20.0      # 网格 / 活动区(四边留 2)

PATH_RE = re.compile(r'<path\b([^>]*?)/?>', re.S)
ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')


def parse(svg_path):
    t = open(svg_path, encoding="utf-8").read()
    vb = re.search(r'viewBox="([\d.\s-]+)"', t)
    if not vb:
        raise SystemExit("没有 viewBox: " + svg_path)
    _, _, w, h = [float(x) for x in vb.group(1).split()]
    paths = []
    for m in PATH_RE.finditer(t):
        a = dict(ATTR_RE.findall(m.group(1)))
        if "d" in a:
            paths.append(a)
    return w, h, paths


def filled(a):
    """这条路径是不是真的要填充。

    源 SVG 是别人用 vd2svg.mjs 从 vector drawable 转出来的，那个转换器把
    **没写 fillColor** 的路径（Android 语义 = 不填充）转成了 6 位 `fill="#ffffff"`；
    真正要填充的转成 8 位 `#ffffffff`，透明的转成 `none`。
    照单全收会让 sky / sunshade / 后除雾变成一块白疙瘩（2026-08-15 已在模拟器上见过）。
    逐条对过 nav_common_ac_rear_defrost 的原始 vd：11 条路径一一对应，规律成立。
    """
    f = a.get("fill", "none")
    return f != "none" and len(f) != 7


def emit_path(a, indent):
    out = ['%s<path' % indent, '%s    android:pathData="%s"' % (indent, a["d"])]
    if filled(a):
        out.append('%s    android:fillColor="#FFFFFFFF"' % indent)
        if a.get("fill-rule") == "evenodd":
            out.append('%s    android:fillType="evenOdd"' % indent)
    if a.get("stroke", "none") != "none":
        out.append('%s    android:strokeColor="#FFFFFFFF"' % indent)
        out.append('%s    android:strokeWidth="%s"' % (indent, a.get("stroke-width", "1")))
        out.append('%s    android:strokeLineCap="%s"' % (indent, a.get("stroke-linecap", "round")))
        out.append('%s    android:strokeLineJoin="%s"' % (indent, a.get("stroke-linejoin", "round")))
    out[-1] += " />"
    return "\n".join(out)


def convert(name, svg_name, rotate=0, bbox=None, tweak=1.0):
    w, h, paths = parse(os.path.join(SRC, svg_name + ".svg"))
    if bbox:
        bx, by, bw, bh = bbox                 # 内容包围盒 → 归一化到 20x20 活动区
        s = ACTIVE / max(bw, bh) * tweak
        tx = GRID / 2 - (bx + bw / 2) * s
        ty = GRID / 2 - (by + bh / 2) * s
    else:
        s = GRID / max(w, h)                  # 没量过包围盒就退回按 viewBox 缩
        tx, ty = (GRID - w * s) / 2, (GRID - h * s) / 2

    body = "\n".join(emit_path(a, "            ") for a in paths)
    px, py = (bbox[0] + bbox[2] / 2, bbox[1] + bbox[3] / 2) if bbox else (w / 2, h / 2)
    rot = ('\n            android:rotation="%g"'
           '\n            android:pivotX="%g"\n            android:pivotY="%g"' % (rotate, px, py)) if rotate else ""
    return '''<?xml version="1.0" encoding="utf-8"?>
<!-- 来源: %s.svg (BYD 原厂, 见 tools/icon_map.json)。别手改，改映射表重跑 tools/svg2vd.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <group
        android:translateX="%g"
        android:translateY="%g">
        <group
            android:scaleX="%g"
            android:scaleY="%g"%s>
%s
        </group>
    </group>
</vector>
''' % (svg_name, tx, ty, s, s, rot, body)


def main():
    dry = "--dry" in sys.argv
    cfg = json.load(open(os.path.join(ROOT, "tools/icon_map.json"), encoding="utf-8"))
    jobs = [(k, v, 0) for k, v in cfg["map"].items() if not k.startswith("_")]
    jobs += [(k, v[0], v[1]) for k, v in cfg["_rotate"].items() if not k.startswith("_")]

    bboxes = {k: v for k, v in cfg["_bbox"].items() if k != "_note"}   # 派生字形名本身以 _ 开头，别按前缀过滤
    tweaks = {k: v for k, v in cfg["_tweak"].items() if k != "_note"}

    for name, svg, rot in sorted(jobs):
        bb = bboxes.get(svg.replace("_w_icon", ""))
        if bb is None:
            print("  ! %s 没量过包围盒，退回按 viewBox 缩" % svg)
        xml = convert(name, svg, rot, bb, tweaks.get(name, 1.0))
        out = os.path.join(DST, name + ".xml")
        if not dry:
            open(out, "w", encoding="utf-8").write(xml)
        print("%-22s <- %s%s" % (name, svg, "  (旋转 %g°)" % rot if rot else ""))
    print("\n%d 个%s" % (len(jobs), "（--dry 未写盘）" if dry else " 已写入 res/drawable"))
    gaps = [k for k in cfg["_gap"] if not k.startswith("_")]
    print("缺口 %d 个保持原样: %s" % (len(gaps), " ".join(gaps)))


if __name__ == "__main__":
    main()
