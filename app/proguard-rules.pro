# ── 内置提权服务:必须 keep,否则 R8 当死代码删掉 ──
#
# server 包里的类由独立 app_process JVM 按类名启动、从 main(String[]) 进入
# (见 SelfAdbPrivileged 那句 "CLASSPATH=<apk> app_process … BlankServer")。
# 对 app 自己的入口图不可达,不 keep 的话:
#   · BlankServer.main / BlankService 被当死代码整个删掉(实测 usage.txt 确认)
#   · 于是 app_process 加载不到类,Shell.ready() 永远 false,车控/画中画/Dock 集体失灵
# keep 整包(含 main 与全部成员),类名也别改 —— 命令行按 getName() 拼,自洽但删不得。
-keep class com.liubai.blank.privileged.server.** { *; }

# JNI(游戏核心 Retro.native*)不用额外写:AGP 默认规则
#   -keepclasseswithmembernames class * { native <methods>; }
# 已保住带 native 方法的类名+方法名,Java_com_liubai_blank_game_Retro_* 符号对得上(实测 mapping 确认)。
