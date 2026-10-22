# 第三方组件

## 依赖库(Gradle)

| 组件 | 许可证 |
|---|---|
| AndroidX(appcompat / viewpager2 / dynamicanimation / webkit) | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| ZXing core | Apache-2.0 |
| Shizuku API / provider | Apache-2.0 |

## 源码中包含

| 文件 | 来源 | 许可证 |
|---|---|---|
| `app/src/main/cpp/libretro.h` | libretro API 头文件(RetroArch team) | MIT(见文件头) |

## 随包附带的模拟器核心

`app/src/main/assets/cores/` 下是 libretro 官方 buildbot 编译的核心,作为**独立程序**在运行时加载,
不属于本项目的 GPL-3.0 授权范围,各自适用自己的许可证:

| 核心 | 上游 | 许可证 |
|---|---|---|
| fceumm | libretro/libretro-fceumm | GPL-2.0 |
| mgba | mgba-emu/mgba | MPL-2.0 |
| fbneo | libretro/FBNeo | FBNeo 许可(非商业) |
| fbalpha2012(cps1 / cps2 / neogeo) | libretro/fbalpha2012* | FBA 许可(非商业) |
| mame2003_plus | libretro/mame2003-plus-libretro | MAME 许可(非商业) |

FBNeo / FBA / MAME 的许可证禁止商业使用,分发本项目的安装包时请遵守。

## 图标与素材

- 部分车控图标的字形参照了 DiLink 原厂图标并重新绘制(`tools/icons_src/`)。
- 内置壁纸「黑夜猫」来源于第三方素材。
