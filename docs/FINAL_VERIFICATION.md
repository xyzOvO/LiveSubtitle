# v2 最终验证记录

日期：2026-11-14（Asia/Shanghai）

## 交付物

- APK：`D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`
- 大小：`1,050,595,654` B
- SHA-256：`f4b60531b79db3420cf86ee3c3a582d3f86bc3994ee1e98f52e8ad4d26414a22`
- 源码与文档压缩包：`D:/AI库/一次归档/LiveSubtitleAndroid-v2-source.zip`（不含 `.onnx` / `.aar` / `.tar.bz2` / `.apk` 大二进制）

## Fresh 证据

| 检查 | 结果 |
| --- | --- |
| `python tools/check_v2_contract.py` | `PASS: v2 static contract checks passed.` |
| `python tools/check_project.py` | `PASS: structure / resources / v2 four-route assets / runtime-zero-network checks passed.` |
| `python tests/test_spm_viterbi.py` | `SPM_TEST_PASS: pure-Kotlin algorithm contract matches official SentencePiece pieces and mapped ids.` |
| Gradle `:app:assembleDebug` | `BUILD SUCCESSFUL in 33s`; `37 actionable tasks: 37 executed` |
| `python tools/verify_v2_apk.py` | `VERIFY_OK f4b60531b79db3420cf86ee3c3a582d3f86bc3994ee1e98f52e8ad4d26414a22` |
| ZIP 结构 | `484` entries；`36` expected assets；`1` 个 `lib/arm64-v8a/libonnxruntime.so` |
| `aapt2 dump badging` | package `com.wo.livesub`，minSdk `29`，targetSdk `34`，无 INTERNET / ACCESS_NETWORK_STATE |
| `apksigner verify --verbose --print-certs` | `Verifies`，v2 scheme true |

## ADB 真机状态（用户确认后重试成功）

- 设备：`7b4ac943` / Xiaomi `2201122C` / Android 10 / API 29 / `arm64-v8a`。
- 首次尝试曾被 MIUI 用户确认门拦截：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；未绕过安全确认。
- 用户要求重试后：`adb -s 7b4ac943 install -r D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk` 成功，输出 `Success` / `INSTALL_OK`；系统广播 `PACKAGE_ADDED dat=package:com.wo.livesub`。
- `pm path com.wo.livesub`：`package:/data/app/com.wo.livesub-Sm9-W6jqMjFEoxsOd5FCYQ==/base.apk`。
- `dumpsys package com.wo.livesub`：`versionCode=1`、`versionName=0.1.0`、`minSdk=29`、`targetSdk=34`、`apkSigningVersion=2`、`primaryCpuAbi=arm64-v8a`、`firstInstallTime=2026-11-14 05:26:03`；请求权限仅 `RECORD_AUDIO` / `SYSTEM_ALERT_WINDOW` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` / `POST_NOTIFICATIONS`（及系统动态 receiver 权限），无 `INTERNET` / `ACCESS_NETWORK_STATE`。
- 冷启动 smoke：`monkey -p com.wo.livesub -c android.intent.category.LAUNCHER 1` 后，`ActivityTaskManager` 显示 `Displayed com.wo.livesub/.MainActivity: +584ms`；`logcat` 无 `FATAL EXCEPTION` / `AndroidRuntime`，`LAUNCH_NO_CRASH True`。
- 诚实边界：这证明 APK 已真机安装且主界面可启动；仍未做音频采集、ASR、MT 端到端翻译质量与延迟实测，也没有授权 MediaProjection / 悬浮窗 / 录音后跑完整字幕流程。

## 需求逐项

| 用户要求 | 落地 |
| --- | --- |
| 默认源语音英语 | `lang_en android:checked="true"`；`normalizeLang(null)` 返回 `en` |
| 输出语言可选 | `target_zh` / `target_ja` / `target_en`；`mt/en`、`mt/en_ja`、`mt/ja`、`mt/ja_en` 四路由 |
| 双语 / 保留原文 | `DisplayMode`、`renderLine`、复制 / SRT / 浮层 / 历史共用；失败保留 `item.source` |
| 翻译更准 | 官方 `source.spm` → `spm.tsv` → 纯 Kotlin unigram Viterbi / BPE greedy → 合并 `source_vocab.tsv` 查 id |
| 翻译更快 | encoder hidden 一次；3 条路由启用 `decoder_with_past` KV cache；`setIntraOpNumThreads(4)` |
| 柔和工具风 UI | `design.md` 色值、16dp 卡片、最大 480dp 单栏、克制禁用/空/错误状态 |
| 译声体验纪律 | 有界历史 / 草稿 / 队列、原文失败保留、手动补译、历史回看不强制滚底、native 资源 finally 释放 |
| Sakura 可行性 | `docs/SAKURA_FEASIBILITY.md`：不内置，不作实时默认路径；披露许可证、体积、NDK 与真机 benchmark 缺口 |
| 重新编译签名交付 | Fresh Gradle + APK 结构 / 权限 / 签名 / 资产验证通过，APK 已复制到交付目录 |

## 已知边界

- 真机安装与冷启动 smoke 已通过；未做音频采集 / ASR / MT 端到端质量与延迟实测（需要用户授权 MediaProjection、录音与悬浮窗，并播放可采集的系统音）。
- `ja→zh` 官方 ONNX 导出无 present，仍走逐前缀 fallback；其余三路由使用 KV cache。
- 四套轻量 Marian 不是 Sakura 级 LLM；再分发模型前仍需自核各模型许可证。
- 启动前台服务时同步加载几百 MB ONNX 仍可能造成 ANR；若真机复现，后续应把模型加载挪到启动协程并显示加载态。
