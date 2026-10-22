#!/usr/bin/env python3
"""图标体检。

2026-08-15 换了尺子：以前量线宽（只量得到描边型，对填充型是瞎的），
现在**验出处** —— 把 icon_map.json 里的每个图标重新转一遍，和磁盘上的逐字节比对。
一致 = 这个字形确实是从 tools/icons_src 那份源 SVG 按产线产出的，没人手改过。
这把尺子管得住全部构造（描边/填充/混合），比线宽强。

用法: python3 tools/icon_lint.py
"""
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import svg2vd

ROOT = svg2vd.ROOT
DST = svg2vd.DST
GRID = svg2vd.GRID


def main():
    cfg = json.load(open(os.path.join(ROOT, "tools/icon_map.json"), encoding="utf-8"))
    bboxes = {k: v for k, v in cfg["_bbox"].items() if k != "_note"}   # 派生字形名本身以 _ 开头，别按前缀过滤
    tweaks = {k: v for k, v in cfg["_tweak"].items() if k != "_note"}
    jobs = [(k, v, 0) for k, v in cfg["map"].items() if not k.startswith("_")]
    jobs += [(k, v[0], v[1]) for k, v in cfg["_rotate"].items() if not k.startswith("_")]

    bad = 0
    for name, svg, rot in sorted(jobs):
        p = os.path.join(DST, name + ".xml")
        if not os.path.exists(p):
            print("FAIL %-22s 映射表里有，res/drawable 里没有" % name); bad += 1; continue
        want = svg2vd.convert(name, svg, rot, bboxes.get(svg.replace("_w_icon", "")),
                              tweaks.get(name, 1.0))
        if open(p, encoding="utf-8").read() != want:
            print("FAIL %-22s 与产线产出不一致（有人手改过？跑 tools/svg2vd.py 覆盖回来）" % name)
            bad += 1

    print("\n%d/%d 出自产线" % (len(jobs) - bad, len(jobs)))

    gaps = [k for k in cfg["_gap"] if not k.startswith("_")]
    live = [g for g in gaps if os.path.exists(os.path.join(DST, g + ".xml"))]
    if live:
        print("\n未纳入产线 %d 个（BYD 那批没有对应字形，还是上一轮的来源，风格对不齐）：" % len(live))
        print("  " + " ".join(live))
        for g in live:
            why = cfg["_gap"][g]
            if why:
                print("    %-16s %s" % (g, why))

    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
