#!/usr/bin/env python3
"""生成 _derived_hotspot.svg：中心圆点 + 上下各两道圆角弧带。

尺度全部照 status_wifi_w_icon.svg 抄：同一个 viewBox、同样的圆点半径、
同样的弧带宽度（wifi 那三道弧的圆角半径 360.9 → 带宽 721.8）。
所以两个图标是同一套笔法，摆在一排不会一个粗一个细。
"""
import math

VB = 8533.3
CX = CY = 4266.65
DOT_R = 482.9          # wifi 的圆点半径
BAND = 721.8           # 弧带宽度
RC = BAND / 2.0

# 两道弧的内半径。wifi 那三道大致等距（1637→3231→4964 的外沿），这里取同量级
RINGS = [1250.0, 2400.0]
HALF = 50.0            # 每道弧张角的一半（度）


def pt(r, deg):
    a = math.radians(deg)
    return CX + r * math.cos(a), CY - r * math.sin(a)


def band(center_deg, r1):
    """一道圆角弧带。center_deg=90 是正上方，270 是正下方。"""
    r2 = r1 + BAND
    rm = (r1 + r2) / 2.0
    a1, a2 = center_deg - HALF, center_deg + HALF
    ax, ay = pt(r1, a1)
    bx, by = pt(r1, a2)
    cx_, cy_ = pt(r2, a2)
    dx, dy = pt(r2, a1)
    # 内弧 a1→a2 角度递增 = 屏幕上逆时针 = sweep 0
    # 外弧 a2→a1 角度递减 = 顺时针 = sweep 1
    # 两端的圆角帽向外凸，遍历方向和相邻弧一致
    return (f"M{ax:.1f},{ay:.1f}"
            f"A{r1:.1f},{r1:.1f} 0 0 0 {bx:.1f},{by:.1f}"
            f"A{RC:.1f},{RC:.1f} 0 0 1 {cx_:.1f},{cy_:.1f}"
            f"A{r2:.1f},{r2:.1f} 0 0 1 {dx:.1f},{dy:.1f}"
            f"A{RC:.1f},{RC:.1f} 0 0 1 {ax:.1f},{ay:.1f}Z")


def dot():
    r = DOT_R
    return (f"M{CX - r:.1f},{CY:.1f}"
            f"a{r:.1f},{r:.1f} 0 1 0 {2 * r:.1f},0"
            f"a{r:.1f},{r:.1f} 0 1 0 {-2 * r:.1f},0Z")


paths = [dot()]
for r in RINGS:
    paths.append(band(90, r))    # 上
    paths.append(band(270, r))   # 下

body = "".join(f'<path d="{p}" fill="#ffffffff"/>' for p in paths)
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {VB} {VB}">'
       f'{body}</svg>')
print(svg)


# ---- 顺带算包围盒，喂给 icon_map.json 的 _bbox ----
# 弧带的外轮廓 = 外弧 + 两端圆角帽。帽按整圆采样：帽心在 (r1+r2)/2 上、半径 (r2-r1)/2，
# 整圆最外点正好落在 r2 上，和外弧齐平，所以这是准确上界不是粗放估计。
xs, ys = [CX - DOT_R, CX + DOT_R], [CY - DOT_R, CY + DOT_R]
for center in (90.0, 270.0):
    for r1 in RINGS:
        r2 = r1 + BAND
        rm = (r1 + r2) / 2.0
        for i in range(361):
            t = center - HALF + (2 * HALF) * i / 360.0
            x, y = pt(r2, t)
            xs.append(x); ys.append(y)
        for edge in (center - HALF, center + HALF):
            hx, hy = pt(rm, edge)
            for i in range(361):
                a = math.radians(i)
                xs.append(hx + RC * math.cos(a)); ys.append(hy + RC * math.sin(a))
import sys
print(f'_bbox: [{min(xs):.1f}, {min(ys):.1f}, {max(xs) - min(xs):.1f}, {max(ys) - min(ys):.1f}]',
      file=sys.stderr)
