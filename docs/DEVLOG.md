# 开发日志（0.1.x → 1.0.0）

> 这是 2026-11-13 ~ 11-14 两天迭代的原始记录：每一轮改了什么、真机埋点数字、踩过的坑与当时的诚实边界。
> 面向使用者的说明见仓库根目录的 [README.md](../README.md)；这里保留的是过程证据，其中出现的版本号、包大小、哈希都是当时的产物。

# 浮译 LiveSubtitleAndroid（v3 · 默认英语 · 输出语言固定中文）

可侧载 Android APK；运行时零联网，模型随 APK 预置。默认源语音英语，可切日语，输出语言固定中文；支持双语显示、原文保留、手动补译、悬浮字幕。

## 结论先行

- 运行时不联网：Manifest 不授予 INTERNET；ORT AAR 注入的 INTERNET / ACCESS_NETWORK_STATE 用 `tools:node="remove"` 移除。
- 翻译准确性修复：旧版用字符最长匹配近似 SPM；v2 从官方 `source.spm` 生成 `spm.tsv`，纯 Kotlin 按 model_type 运行 unigram Viterbi 或 BPE greedy，得到 piece 字符串后再查 HF 合并 `source_vocab.tsv` 的 id。
- 翻译速度修复：encoder hidden 只算一次；en→zh 使用 `decoder_with_past_model_quantized.onnx` 的 KV cache；ORT `setIntraOpNumThreads(2)`（与 ASR 的 2 线程对齐，避免抢核发热）；ja→zh 因官方导出无 present，保留逐前缀 fallback。
- UI：按 `design.md` 的柔和工具风重做，Mist 背景、Deep Teal 主色、16dp 卡片、单栏最大 480dp、克制的禁用/空/错误状态。
- Sakura：详见 `docs/SAKURA_FEASIBILITY.md`；本轮不内置 `CC BY-NC-SA 4.0` 7B 权重。
- ADB：设备 `7b4ac943` / `2201122C` 已连接；首次尝试被 MIUI `INSTALL_FAILED_USER_RESTRICTED` 拦截，用户确认后重试已成功安装。启动 smoke 通过，应用 `com.wo.livesub/.MainActivity` 正常显示；静态自检仍不冒充真机音频/ASR/MT 端到端质量结论。

## 用户怎么用

1. 安装签名 APK，允许「未知来源」；首次打开授予录音、通知与悬浮窗权限。
2. 源语音默认英语，可切日语；输出语言固定中文；勾选「双语显示（原文 + 译文）」后历史、复制、SRT、浮层都显示原文与译文。
3. 点「开始字幕」，在 MediaProjection 授权弹窗确认；切语言前先停止。
4. 翻译超时会保留原文，不丢句；点「补译上一句」重试。历史可手动回看，不在每次状态刷新时强制滚到底。

## 模型路由

| 源语音 → 输出语言 | assets 目录 | 说明 |
| --- | --- | --- |
| en → zh | `mt/en` | `Xenova/opus-mt-en-zh` quantized + `>>cmn_Hans<<` prefix |
| ja → zh | `mt/ja` | `ruozhi521/shiyin-mt-ja-zh-onnx`，BPE `spm.tsv`，无 with_past |
| 源语 == 目标语 | 无模型 | `IdentityTranslator` 原文透传 |

v3 起裁掉英日互译（`mt/en_ja`、`mt/ja_en`，约 291MB）：本 App 的定位是给直播 / 影视配中文字幕，英日互译没有使用场景。被裁掉的两套资产临时存放在 `D:/AI库/一次归档/_trimmed_assets/`，需要恢复时移回 `app/src/main/assets/mt/` 并还原 `ModelAssets.routeDir` 的分支。

每套资产含 `encoder_model.onnx`、`decoder_model.onnx`、`source_vocab.tsv`、`target_vocab.tsv`、`config.tsv`、`spm.tsv`；en 另有 `decoder_with_past_model_quantized.onnx`。`spm.tsv` 六列：`piece<TAB>score<TAB>type<TAB>unk_penalty<TAB>model_type<TAB>unk_id`。`decoder_with_past` 的 `past_key_values.*` 由首步 `present.*` 映射，后续每步只喂最新 token。

## 构建与验证

在本机 Windows 中文路径下 AGP 会拒绝出包，正式构建复制到纯 ASCII `D:/androiddev/build/LiveSubtitleAndroid`：

```bash
JAVA_HOME=D:/androiddev/jdk/jdk-17.0.21+9
ANDROID_HOME=D:/androiddev/sdk
GRADLE_USER_HOME=D:/androiddev/gradle-home
D:/androiddev/gradle-8.7/bin/gradle.bat :app:assembleDebug --no-daemon
```

依赖：JDK 17、Android SDK 34、Gradle 8.7、静态链接 sherpa-onnx AAR、onnxruntime-android 1.29.0。构建机联网下载模型，APK 运行时零联网。

Fresh 验证顺序：

1. `python tools/check_project.py`
2. `python tools/check_v2_contract.py`
3. `python tests/test_spm_viterbi.py`
4. `gradle :app:assembleDebug`
5. APK 结构、assets、`aapt2 dump badging`、`apksigner verify`
6. `adb install -r` 与 `adb logcat` smoke

静态自检 PASS 不代表 Gradle / 真机验证通过；任何「已完成」都必须附 fresh 证据。

## 工程纪律（对照 yisheng-releases）

- 有界历史 `MAX_HISTORY = 200`、有界草稿 `MAX_DRAFT_CHARS = 512`。
- `Channel(capacity = 8, DROP_OLDEST)` 过载保护；旧译句被新定稿句作废。
- 草稿改写只补译最新版；一句定稿后才翻译。
- 失败保留原文、手动补译替换原行，不新增重复字幕。
- 历史回看不被自动滚屏打断；SRT / 复制 / 浮层共用 `renderLine`。
- native 资源早退 `finally` 释放；失败状态 `failureStatus` 不被 `onDestroy()` 覆盖。
- 超时与取消分开处理，SRT 时间戳只是会话内相对偏移，不冒充端到端延迟。

## 已知边界

- Android 10+，arm64-v8a；MediaProjection 每次会话需用户授权。
- 被抓 App 若 `allowAudioPlaybackCapture=false` / `ALLOW_CAPTURE_BY_NONE`，系统音会静音，浮层在但无字幕；不做合规绕过。
- ASR 是 offline transducer / whisper + VAD 的模拟流式，不是原生流式模型。
- 英文/日文 MT 是轻量 Marian，非 Sakura 级 LLM；输出质量仍需在真机音频场景里听评。
- 本机 Python ORT 1.32 有 native crash，PC 侧只做 ONNX graph / SPM 静态验证，质量与延迟以 ADB 真机日志为准。
- 再分发模型前请自核各模型许可证；Sakura 权重不随包分发。


## 补充披露

- 两套轻量 Marian 模型均随 APK 预置；启动前台服务时同步加载几百 MB ONNX 仍可能造成 ANR，若真机复现，后续应把模型加载挪到启动协程并在 UI 显示加载态。
- 静态自检 PASS 只证明资源、权限、模型资产与契约自洽，不代替 Gradle 编译或 ADB 真机验证。

## 最终交付与 Fresh 证据 (2026-11-14)

- 交付 APK：`D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`（以下两行指纹为**上一版**产物；该交付路径已被本轮新包覆盖，本轮指纹见文末「本轮出包」小节）
- 上一版大小：`1,050,595,654` B；上一版 SHA-256：`f4b60531b79db3420cf86ee3c3a582d3f86bc3994ee1e98f52e8ad4d26414a22`
- `tools/verify_v2_apk.py` 输出 `VERIFY_OK`；`484` 个 ZIP entries；`36` 个 expected assets；恰好 1 个 `lib/arm64-v8a/libonnxruntime.so`；`aapt2 dump badging` 无 INTERNET / ACCESS_NETWORK_STATE；`apksigner verify` Verifies。
- 静态三连：`tools/check_v2_contract.py` PASS、`tools/check_project.py` PASS、`tests/test_spm_viterbi.py` PASS。
- Fresh Gradle `:app:assembleDebug`：`BUILD SUCCESSFUL in 33s`，`37 actionable tasks: 37 executed`；3 条 Kotlin warning 不影响出包。
- ADB 真机 smoke：首次 `adb install -r` / `pm install -r` 曾返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`（MIUI 用户确认门）；用户确认后重试 `adb -s 7b4ac943 install -r <APK>` 成功。`pm path com.wo.livesub` 返回 `package:/data/app/com.wo.livesub-Sm9-W6jqMjFEoxsOd5FCYQ==/base.apk`，`dumpsys package` 显示 `versionName=0.1.0`、`minSdk=29`、`targetSdk=34`、`apkSigningVersion=2`、请求权限中无 `INTERNET` / `ACCESS_NETWORK_STATE`。`monkey` 启动 `MainActivity` 后 `ActivityTaskManager` 显示 `Displayed com.wo.livesub/.MainActivity: +584ms`，logcat 无 `FATAL EXCEPTION` / `AndroidRuntime`。这证明安装与冷启动 smoke 通过；未做音频采集 / ASR / MT 端到端质量与延迟实测。
- Sakura 可行性报告：`docs/SAKURA_FEASIBILITY.md`；结论不内置权重、不作实时默认路径，未声称在 `2201122C` 上实时可用。
- 详细验收记录：`docs/FINAL_VERIFICATION.md`。

## 安装后补记（用户确认后重试，2026-11-14 05:26）

- `adb -s 7b4ac943 install -r D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：成功，`INSTALL_OK`。
- `pm path com.wo.livesub`：`package:/data/app/com.wo.livesub-Sm9-W6jqMjFEoxsOd5FCYQ==/base.apk`。
- `dumpsys package com.wo.livesub`：`versionName=0.1.0`，`apkSigningVersion=2`，`primaryCpuAbi=arm64-v8a`，请求权限无网络权限。
- `monkey -p com.wo.livesub -c android.intent.category.LAUNCHER 1` 后：`Displayed com.wo.livesub/.MainActivity: +584ms`；无 `FATAL EXCEPTION` / `AndroidRuntime`。
- 之前报告中的「安装被 MIUI 用户确认拦截」是重试前的状态；此处以 fresh ADB 结果为准。

## 本轮源码改动：首字延迟优先（2026-11-14，已重新出包）

目标约束：保持零联网、首字延迟优先（快 > 最高质量）、面向直播 / 影视长句。本轮只落 A4 与 B 组，不改架构、不换模型、不加权限。

- **A4 首字即显**：`Translator.translate` 新增可选 `onPartial`，`NeuralTranslator` 每 2 步或遇标点回调已生成的译文前缀；`SubtitleState.partialTranslation` 驱动浮层与主界面即时显示。前缀只追加已生成 token、不猜后半句，**不写入历史行 / SRT / 复制**；定稿、失败、作废、清屏时清空。SOURCE_ONLY 模式不显示。
- **B 组解码提速**：首步产出的 12 个 `*.encoder.*` cross-attn KV 与 encoder mask 建成常驻 `OnnxTensor` 复用（ORT 1.29 Java 无 IoBinding，已核实）；末尾只做一次 `decodeTarget` 且 Regex 预编译；`argmax` 一次批量读取整行 logits；目标上限按源长收敛 `min(128, 2×srcLen+12)`；连续重复 2/3-gram 直接收尾，避免不吐 EOS 时跑满 128 步；`decodeNoCache` 用 `run(inputs, setOf(logits))` 跳过 present 输出。ORT 会话显式 `SEQUENTIAL` + `interOp=1` + `ALL_OPT`，`setIntraOpNumThreads(4)` 保持不变。
- **稳定性**：`OrtEnvironment.getEnvironment()` 是进程级单例，不再在 `close()` 里释放（否则二次启动会静默退回词典模式）；`create` 失败打 `Log.e` 并在状态栏标「模型加载失败」。
- **不丢句**：队列容量放大到基准 4 倍；过载跳过、队列不可用、被新句作废、服务停止前都保留原文行，补译句只替换原行；用户仍可用「补译上一句」重试。
- **预热与埋点**：翻译协程内串行跑一次 dummy 翻译预热 ORT；日志 tag `LiveSubPerf`（`encMs / decMs / steps / limit / totalMs`）与 `LiveSubLatency`（`firstPartialMs / sentenceMs / warmup / queueFullKeepSource`）。`firstPartialMs` 从 ASR 定稿到首个译文前缀，**不是端到端首字延迟**（语音起点埋点仍在 ASR 侧待补）。

### Fresh 证据（本轮）

- `python tools/check_v2_contract.py` → `PASS`；`python tools/check_project.py` → `PASS`；`python tests/test_spm_viterbi.py` → `SPM_TEST_PASS`。
- 纯 ASCII 路径 `D:/androiddev/build/LiveSubtitleAndroid` 跑 `gradle :app:compileDebugKotlin --offline --no-daemon` → `BUILD SUCCESSFUL`（2 条与 env/options 资源释放相关的既有 Kotlin warning，不影响编译）。

### 诚实边界

- 本轮已重新 clean 出 APK、完成本版 ADB 安装、冷启动与 **MT 段埋点实测**（证据见文末），但**端到端首字延迟（语音起点 → 浮层首字）与翻译质量仍无实测数字**，且无旧包 A/B 基线，不能宣称提速比例。上一节 ADB smoke 记录属于旧包；本版证据见文末两节。
- A2（原生流式 ASR）、A3（VAD 短段策略）、beam search、更强 MT 模型 / 双引擎批量精修、稳定前缀增量翻译（A1）均未落地。A1 会改掉「草稿不翻译、不猜后半句」的产品纪律，需单独确认。
- `ja→zh` 仍走逐前缀 fallback（官方导出无 present）；ORT EP（Xnnpack / NNAPI / QNN）与线程数组合仍需真机 A/B 后才能定值，本轮未开启。
- 所有性能收益均为代码路径分析，未附真机数字；请以 `LiveSubPerf` / `LiveSubLatency` 的 ADB 日志做 A/B 验收。

## 本轮出包（2026-11-14，首字延迟优先版；交付路径已被 07:21 合并版覆盖）

- 交付 APK：`D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`（同时同步到 `app/build/outputs/apk/debug/app-debug.apk`）。
- 大小：`1,050,612,038` B（较上一版 +15,384 B，来自本轮 Kotlin 改动）；SHA-256：`63b744740e2d0955c237ec45d0b8afdab5f7b770fcc93caa021e4e364a9520e5`。
- 纯 ASCII 路径 `D:/androiddev/build/LiveSubtitleAndroid` 执行 `gradle :app:clean :app:assembleDebug --offline --no-daemon` → `BUILD SUCCESSFUL in 1m 3s`，`38 actionable tasks: 37 executed`；3 条既有 Kotlin warning（NeuralTranslator 两处冗余变量、SentencePieceTokenizer 一处 `!!`）不影响出包。
- `python tools/verify_v2_apk.py` → `VERIFY_OK`；`484` 个 ZIP entries；`36` 个 expected assets；恰好 1 个 `lib/arm64-v8a/libonnxruntime.so`；`aapt2 dump badging` 无 INTERNET / ACCESS_NETWORK_STATE；`apksigner verify` Verifies。
- 额外核验：解压 5 个 `classes*.dex` 检索本轮新增符号 `onPartial` / `partialTranslation` / `LiveSubPerf` / `LiveSubLatency` / `queueFullKeepSource` / `firstPartialMs` / `repeatedTail` / `targetLimit` 全部 FOUND，确认新代码确实进包，不是旧包复用。
- 本版已完成 ADB 安装与冷启动 smoke（证据见下一节）；安装未触发 MIUI 用户确认拦截。请用 `adb logcat -s LiveSubPerf LiveSubLatency` 采集首字延迟并与旧包做 A/B。静态验证、编译与冷启动通过都不等于性能收益已实测。

## 本版 ADB 安装与冷启动（2026-11-14 06:17，属 63b74474… 版）

- `adb -s 7b4ac943 install -r D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk` → `Performing Streamed Install / Success`，本次未触发 MIUI「INSTALL_FAILED_USER_RESTRICTED」用户确认拦截。
- `pm path com.wo.livesub` → `package:/data/app/com.wo.livesub-2E4mHSWWUSC0DAmGKXc-wQ==/base.apk`；设备端 `sha256sum` = `63b744740e2d0955c237ec45d0b8afdab5f7b770fcc93caa021e4e364a9520e5`，与本地交付包逐字节一致，确认装的是本版而非旧包。
- `dumpsys package`：`versionName=0.1.0`、`versionCode=1`、`minSdk=29`、`targetSdk=34`、`apkSigningVersion=2`、`primaryCpuAbi=arm64-v8a`、`lastUpdateTime=2026-11-14 06:17:45`；请求权限无 INTERNET / ACCESS_NETWORK_STATE；`RECORD_AUDIO granted=true`，appops `SYSTEM_ALERT_WINDOW: allow`、`PROJECT_MEDIA: allow`。
- `monkey -p com.wo.livesub -c android.intent.category.LAUNCHER 1` → `Displayed com.wo.livesub/.MainActivity: +570ms`，`mResumedActivity=MainActivity`；logcat 中 `FATAL EXCEPTION` / `AndroidRuntime` 崩溃计数 0。仅有 MIUI greylist warning 与一条 `Slow Looper main: 337ms late`（主线程渲染抖动，未崩溃）。
- 冷启动阶段**未启动前台服务**；随后用户点「开始」并授权 MediaProjection，服务启动并产出埋点，实测见下一节。

## 本版真机埋点实测（2026-11-14 06:20，ja→zh / 日语歌曲合集）

采集方式：`adb -s 7b4ac943 logcat -c` 后连续 60s 采集 `LiveSubPerf:I LiveSubLatency:I *:S`，合并冷启动后的首轮缓冲；数据来自用户点「开始」并授权 MediaProjection 后真实播放的 B 站日语歌曲合集，`label=onnx-ja-zh`，前台服务 `isForeground=true`。

- **样本量**：`firstPartialMs` n=16、`sentenceMs` n=17（`ok=true` 17/17）、MT 逐句 n=19。
- **首字前缀延迟**（ASR 定稿 → 首个译文前缀）：min 69 / **P50 100** / P95 490 / max 490 ms。
- **整句完成延迟**（ASR 定稿 → 整句译文）：min 88 / **P50 132** / P95 787 / max 787 ms。A4 首字即显在 P50 提前约 32ms、P95 提前约 297ms。
- **MT 内部**：encMs P50 11 / decMs P50 102 / totalMs P50 130 ms；steps P50 3、P95 10；动态上限 limit 16–26（原固定 128 已收敛）；decMs/step 均值 53ms，最大 313.5ms（存在 `steps=2` 但 `decMs=627ms` 的离群样本，疑似 CPU 抢占或降频，未定性）。
- **解码路径 19/19 全是 `noCache`**：`mt/ja/decoder_model.onnx` 官方导出无 present 输出，ja→zh 只能逐前缀 greedy。本轮 KV 常量复用 / `with_past` 优化在这条路由上**完全没有生效**；必须在 en→zh（有 `decoder_with_past`）语向下另测 `cached` 路径才能验收 B 组主项。
- **稳定性**：无 `queueFullKeepSource`、无 `neural MT unavailable`、无 `MT session create failed`、无 warmup failed；`warmup done` 2 次（服务被启动过两次）。浮层窗口存在（`dumpsys window` Window #8 `com.wo.livesub`，appop=SYSTEM_ALERT_WINDOW），MediaProjection appops `allow`。

### 这份数据的边界（不能当提速结论）

- 只测 **MT 段**：`firstPartialMs` 从 ASR 定稿起算，**不含 VAD 等待与 ASR 识别**，不是端到端「语音起点 → 浮层首字」。此前分析认为大头在那一段，需在 ASR 侧补埋点。
- 素材是**日语歌曲合集**，歌声让 ASR 只吐出 2–7 个 token 的极短片段（src P50=2），不代表直播 / 影视连续对白；长句下 `noCache` 的 O(n²) 会更明显。
- **没有 A/B 基线**：旧包已被新包覆盖、设备只装本版，本轮不能给出「比上一版快 X%」。静态自检、编译、冷启动通过同样不是性能证据。
- 译文文本未采集，质量无人工评分；浮层文本未被 `uiautomator dump` 抓到（overlay 窗口不计入），只确认窗口存在。

## 修复：ASR 分段把句子切碎，导致「中日翻译不能完整翻译」（2026-11-14 06:36）

### 核对结论（不是译文被截断）

- 真机截图里浮层与历史全是「え」「何」「大家」「嗯……」「诶？」这类 1–3 词碎片，没有一句成句；同批日志 `src` 只有 2–7 个 token。
- 但 MT 对完整输入能译出整句（「迷ってる」→「诶？你迷路了吗？」），`steps` P50=3 远未撞 `limit` 16–26，`ok=true` 17/17。所以问题在**上游分段**，不在解码收尾。
- 根因 `AsrEngine.kt`（修复前 `:93-96`）：`minSilenceDuration = 0.1f` 让 0.1 秒静音就定稿一段，`minSpeechDuration = 0.25f` 让语气词单独成句，`maxSpeechDuration = 8f` 对长句硬切。每段独立跑 offline ASR → 碎片 → Marian 只能译碎片。`uiautomator dump` 因浮层持续刷新报 `could not get idle state`，改用 `screencap` 截图才拿到证据。

### 改动（2 个文件，不改架构、不引入合并等待）

- `core/AsrEngine.kt`：`minSilenceDuration 0.1f → 0.4f`、`minSpeechDuration 0.25f → 0.3f`、`maxSpeechDuration 8f → 20f`（与 20s buffer 对齐），改后行 `:98-101`；新增 tag `LiveSubAsr` `:45` 与段时长埋点 `:169`（`final durMs=… chars=…`）。
- `core/NeuralTranslator.kt`：`decodeCached` / `decodeNoCache` 记录终止原因并写入 `LiveSubPerf`，改后行 `:150,227,235,242` / `:263,290,295,303,308`。日志新增 `stop=eos|pad|repeat|limit|logits`，用于区分「正常收尾」与「被提前掐断」。
- 静态契约 `check_project.py:227-230` 只固定 `AsrEngine` 的 4 个字面量，VAD 参数不在其中；静态三连仍 PASS。

### Fresh 证据（修复版）

- `gradle :app:clean :app:assembleDebug --offline --no-daemon` → `BUILD SUCCESSFUL in 49s`，`38 tasks / 37 executed`；`compileDebugKotlin` 单独 BUILD SUCCESSFUL（2 条既有 warning）。
- `tools/verify_v2_apk.py` → `VERIFY_OK`；484 entries / 36 assets / 1 个 libonnxruntime.so / 无网络权限 / apksigner Verifies。
- 该版交付 `D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：`1,050,612,038` B，SHA-256 `50c1a10746cfb128a0e95c012f84dd3d0eb5fa2cf0158a0df101a40d29da7935`（覆盖 `63b74474…` 版；交付路径现已被 07:21 合并版覆盖）。
- `adb -s 7b4ac943 install -r` → `Success`；设备端 `sha256sum` 与交付一致，`lastUpdateTime=2026-11-14 06:36:16`。dex 中 `LiveSubAsr` / `final durMs=` / `stop=` 全部 FOUND。

### 诚实边界

- 覆盖安装会杀掉前台服务；用户随后重新点「开始」并授权 MediaProjection，实测结果表明**本节修复无效**（浮层仍是单字碎片），详见下一节。本节只证明「代码与包已修改」，不能宣称译文完整性已修复。
- 调大静音阈值会让定稿多等最多约 0.3s，这是为完整性付的延迟代价；若真机发现首字明显变慢，可再按语向微调。
- 日语歌曲素材本身超出 ASR 训练分布（歌声识别差），与分段问题是两件事；本轮未换 ASR 模型。

## 复测推翻上一节：真根因是音频线程阻塞丢采样（复测 06:50 / 最终交付 06:54）

### 复测事实（用户重新点「开始字幕」后）

- 前台服务 `isForeground=true`，B 站播放 Tanaka Radio 日语广播剧（静态插画 + 日语对白，不是歌曲）。
- `screencap` 截图显示浮层仍是「私 / 我 / え / 嗯…… / はい...」这类单字碎片；`LiveSubAsr` 打出 `final durMs=3164 chars=1`、`durMs=33728 chars=1`、`durMs=7890 chars=1` —— **段确实变长了，识别结果却只有 1 个字符**。
- 清空缓冲连续采集 60 秒只拿到 2 行系统日志、0 条埋点；同批 `LiveSubPerf` 仍是 `src=2..7`、`stop=eos`。
- 顺带一个正面结果：`onnx-en-zh` 语向首次跑出 `cached` 路径（`steps=4 limit=20 stop=eos`），说明 B 组 KV 复用在 en→zh 生效；ja→zh 仍全走 `noCache`（该模型本身无 present 输出）。

### 真根因

- 早期实现把 `recognize()`（offline zipformer，同步阻塞）直接放在 `AudioRecord.read` 的回调链里：`AsrEngine.acceptWaveform` 在音频采集线程上每 200ms 用**整段 buffer（最长 20s）**重跑草稿识别，定稿识别也在同一线程。
- 全长 logcat 里 `W/AudioFlinger: RecordThread: buffer overflow` 成片出现 —— `AudioRecord` 内部缓冲被读线程阻塞撑爆，系统在丢采样。VAD 拿到断续音频，只能吐单字；`durMs=33728` 是 CPU 被长段草稿识别占满的旁证。
- 所以 06:36 那轮调 VAD 阈值**没有治到点子上**：阈值确实让语音段变长，但识别链路仍在丢音频。「VAD 把句子切碎」只是表象之一，不是全部原因。

### 本轮修复（只改 `core/AsrEngine.kt` 一个文件）

| 位置 | 改动 |
|---|---|
| `:15-16` | 引入 `ArrayBlockingQueue` / `TimeUnit` |
| `:50-54` | 新增 `DRAFT_WINDOW_SAMPLES = 2s` 草稿窗口、`MAX_BUFFER_SAMPLES 20s → 4s`、`FRAME_QUEUE_CAPACITY = 200`（≈20s） |
| `:141-167` | 新增独立 daemon `worker` 线程；`acceptWaveform()` `:169-180` **只入队立即返回**（满则丢最旧并打 `frame queue full`）；VAD / 草稿 / 定稿全在 worker 顺序执行 |
| `:182-252` | `processFrame()`：草稿只识别最近 2s 窗口 `:206`；`lastDraftAtMs` 改到识别完成后记录 `:210`，让单线程 worker 自然限流；定稿埋点加 `segSec` `:222` / `recMs` / `text`（截断 40 字）与 `final EMPTY` 分支 `:234` |
| `:256-263` | `close()` 只发信号 + `interrupt()` **不 join**（`Service.onDestroy()` 在主线程，native decode 不可中断，等待会 ANR）；buffer 与 native 资源统一由 worker 退出时清理释放 |
| `:29-35` | 类注释写明「不要再把 recognize 挪回采集线程」 |

`VAD_WINDOW = 512`、`DRAFT_INTERVAL_MS = 200L` 两个契约字面量保持原值；VAD 阈值沿用 06:36 的 `0.4 / 0.3 / 20`。未改 MT、未改 UI、未改权限。

### Fresh 证据

- 静态三连 PASS（`check_v2_contract.py` / `check_project.py` / `test_spm_viterbi.py`）；`compileDebugKotlin` BUILD SUCCESSFUL（3 条既有 warning）。
- `gradle :app:clean :app:assembleDebug --offline --no-daemon` → `BUILD SUCCESSFUL in 48s`，`38 tasks / 37 executed`（最终版；中间 06:50 曾出过一版 `3871bf1a…`，因 `close()` 会在主线程 `join(1.5s)` 被自查推翻，已重新出包覆盖）。
- `tools/verify_v2_apk.py` → `VERIFY_OK`；484 entries / 36 assets / 1 个 libonnxruntime.so / 无网络权限 / apksigner Verifies。
- 该版交付 `D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：`1,050,612,038` B，SHA-256 `7378e75d7dc9be7d0059618956d25dbf1dd08764dc9964c7fc9932c07c2fcb56`（交付路径现已被 07:21 合并版覆盖）。dex 中 `livesub-asr-worker` / `frame queue full` / `segSec=` / `final EMPTY` 全部 FOUND。
- `adb -s 7b4ac943 install -r` → `Success`；设备端 `sha256sum` 与交付一致，`lastUpdateTime=2026-11-14 06:54:32`，主界面已切回前台。

### 真机复测验收（06:59，用户重新点「开始字幕」后）

采集方式：`logcat -d` 导出全部缓冲 + 清空后连续 60s 独立采集，合并 **42 句 ASR 定稿 / 44 次 MT**；素材为 B 站日语对白视频（多人闲谈 / 广播剧，非歌曲）。

| 指标 | 修复前（06:20 / 06:50 复测） | 修复后（06:59） |
|---|---|---|
| `AudioFlinger: RecordThread: buffer overflow` | 成片出现 | **0** |
| `LiveSubAsr chars` 识别字符数 | 06:50 的 3 个样本全是 `chars=1` | P50 **16** / P95 47 / max 59 |
| 成句率 | 无成句 | `chars>=10` **30/42**；`chars>=6` 35/42 |
| 单字碎片 `chars<=2` | 全部 | 5/42（多为「はい」「うん」等语气词） |
| `LiveSubPerf src` tokens | P50 2（范围 2–7） | P50 **7** / P95 16 / max 26 |
| `frame queue full` / `final EMPTY` / `worker frame failed` | — | **0 / 0 / 0** |

- 定稿文本样例（完整日语句）：`私は幼稚園の時に自分の名前をひらがなで書く練習をしていたので得意げにノートに大き…`（59 字 / 11.4s）、`今回は日本の子どもたちがどうやって漢字を習得していくのかということについてお話し…`（50 字 / 10.3s）。截图里可见成句译文「请多多指教」。
- 段长与识别质量正相关：0–3s 段 `chars` P50 10，3–10s 段 22.5，10–25s 段 48.5 —— 说明 VAD 分段与 ASR 都恢复正常，不再是「段变长但只吐 1 字」。
- MT：`stop=eos` 44/44、`ok=true` 42/42、`firstPartialMs` P50 122ms、`sentenceMs` P50 311ms（句子变长、`steps` P50 从 3 升到 7.5，整句耗时上升属正常）。ja→zh 仍全 `noCache`。
- **修前与修后素材不同**（歌曲合集 / 广播剧 → 多人对白），样本量也不对等，上表只作定性对照，不是严格 A/B。

### 已知残留与诚实边界

- 1/42 段出现 `segSec=66.2s`、`recMs=4569ms` 却只识别出「えっ」2 字：`maxSpeechDuration=20f` 没挡住超长段，ReazonSpeech zipformer 对超长音频退化。可选修法是在 worker 里对 >15s 的 segment 切片识别再拼接。
- 端到端首字延迟（语音起点 → 浮层首字）仍无埋点；`firstPartialMs` 只覆盖「ASR 定稿 → 译文前缀」。无旧包 A/B 基线，**不能宣称提速比例**。
- 译文质量无人工评分；5 个短语气词碎片仍会出现在浮层。
- 异步化把「丢采样」变成「有界队列积压」；若日后看到 `frame queue full`，应降低草稿频率或提高 ASR 线程预算，而不是再调 VAD。

## 合并版：超长段兜底 + 端到端埋点 + 浮层交互 + 本地文字翻译（2026-11-14 07:21）

### 1 · 超长段与端到端埋点（`core/AsrEngine.kt`、`service/SubtitleService.kt`）

- javap 核对 sherpa-onnx 1.12.33 AAR：`Vad` 确有 `flush()` / `reset()` / `clear()`（均为 native）。真机实测 `maxSpeechDuration=20f` 挡不住 66 秒段，改由 worker 按墙钟 `MAX_SEGMENT_MS=12s` 主动 `vad.flush()` 强制吐段，随后 `vad.reset()`；`flush()` 若不产出段，用 `segmentSamples`（worker 自累积的 12 秒原始音频）兜底定稿，绝不丢语音。
- `recognizeSegmented()`：>12s 的 segment 按 12s 切片识别后拼接，绕开 ReazonSpeech zipformer 对长音频的退化；日志新增 `slices=` / `forced=`。
- `AsrListener.onFinal` 新增 `speechStartWallMs`（语音起点绝对墙钟），经 `PendingSegment` 透传；Service 在首个译文前缀回调时打 `e2eFirstMs`（语音起点 → 浮层首个译文字）与 `asrMs`（语音起点 → ASR 定稿）。补译路径传 0，不计入实时指标。
- **口径**：语音起点取 VAD 检测到语音的时刻，含 VAD 判定延迟，不是物理声波起点；`e2eFirstMs` 到「写入 StateFlow」为止，不含浮层下一帧渲染。

### 2 · 浮层：滚动回看 / 字号 / 锁定 / 隐藏（`core/OverlayController.kt`、`res/layout/overlay_subtitle.xml`）

| 需求 | 实现 |
|---|---|
| 滚动字幕 + 上滑回看 | 历史从 3 行扩到 60 行，放进固定 320dp×150dp 的 `ScrollView`；默认自动滚到底跟随最新，用户在滚动区上滑即暂停跟随并显示「回到最新」，点击恢复 |
| 字体大小 | 工具条 `A-` / `A+`，范围 10–30sp（步进 2），写 SharedPreferences `overlay_settings` |
| 锁定 | 禁拖动、禁打开主界面、禁调字号 / 隐藏；字幕滚动回看仍可用；锁按钮本身始终可点以解锁 |
| 隐藏 | 收成一个小把手，点击恢复（**锁定态也能恢复**，否则用户无法再解锁）；设置持久化 |
| 手势解耦 | 拖动监听从整个 root 移到左侧把手，避免与字幕上滑抢手势；去掉 `ellipsize="end"`，历史长句不再被视觉截断 |

### 3 · 主页：语向顺序 / 本地文字翻译 / 交互（`MainActivity.kt`、`res/layout/activity_main.xml`）

- 源语音单选顺序改为「英语（选中）→ 日语」，与默认语向一致；`selectedSourceLang()` 逻辑未动。
- 新增「本地文字翻译」卡片：输入框（上限 500 字）、翻译 / 清空 / 复制译文；复用主页已选语向，按需加载一份**独立**的离线 ONNX 会话并缓存，语向变化才重建，Activity 销毁时 `close()`（不关全局 `OrtEnvironment`）。源语 == 目标语时用 `IdentityTranslator`，不加载模型。**无网络权限、无联网调用**。
- 交互反馈：翻译中禁用「翻译」与「复制译文」、显示加载态；空输入 Toast；模型不可用 / 异常有明确文案；结果可选中、可复制。调用 `translate(input, isStale = { false })` 用命名参数，避开尾随 lambda 被解析成 `onPartial` 的老坑。
- **UI 优化范围**：本轮只做「新功能整合 + 状态反馈 + 分区说明」，未做整体视觉改版，避免破坏已验证的行为与静态字符串契约。

### 4 · Fresh 证据

- 静态三连 PASS；`compileDebugKotlin` BUILD SUCCESSFUL；`gradle :app:clean :app:assembleDebug --offline --no-daemon` → `BUILD SUCCESSFUL in 48s`（37 executed）；`verify_v2_apk.py` → `VERIFY_OK`（484 entries / 36 assets / 无网络权限 / apksigner Verifies）。
- 当前交付 `D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：`1,050,655,110` B，SHA-256 `94fa9344565f7ff3e3b29f8da0b54c5a355a6297bedcb19d03586f9015186029`；`adb -s 7b4ac943 install -r` → Success，设备端 hash 一致，`lastUpdateTime=2026-11-14 07:21:10`。dex 中 `e2eFirstMs=` / `forceFlush` / `recognizeSegmented` / `btn_text_translate` / `userViewingHistory` 全 FOUND。
- **真机已验证**：主界面截图确认语向顺序与新卡片布局无错位；文字翻译实测输入 `hello` → 结果「哈啰」，`uiautomator` 读到 `tv_text_result text=哈啰`、`btn_text_copy enabled=true`，logcat `onnx-en-zh cached steps=2 limit=18 stop=eos` / `totalMs=227`，崩溃计数 0。

### 5 · 诚实边界（仍需用户点「开始字幕」后验证）

- 浮层的滚动回看 / 字号 / 锁定 / 隐藏**尚未真机验证**（需要前台服务 + MediaProjection 授权才有浮层）；本轮只验证了代码、编译、包内容与主界面布局。
- `e2eFirstMs` / `asrMs`、`forceFlush` / `slices=` / `forceFlush fallback` 同样还没有真机数据；超长段兜底需要一段连续长对白才能触发。
- 文字翻译与语音字幕各持一份 ONNX 会话，同时运行会争 CPU / 内存（设备 8GB）；设计上是偶发辅助功能，未做并发压制。
- 端到端首字延迟仍无基线对比，不能宣称提速比例；译文质量无人工评分。
- 本地文字翻译只做了 `hello` 单条真机验证：adb 模拟按键配合中文输入法会反复上屏候选词并误触「翻译」，产生无意义文本（如「维基语Name」），属测试手段干扰而非产品缺陷，验证后界面已清空。多语向、长句与译文质量未评测。

## 浮层：可调尺寸 + 背景透明度 / 纯文字歌词态（2026-11-14 07:54）

### 1 · 交互

| 需求 | 实现 |
|---|---|
| 调整悬浮窗大小 | 右下角 `↘` 手柄：**按住拖动**按 rawX/rawY 增量自由改宽高；**点击**（未拖动）循环 4 档预设 `260×160 / 320×210 / 380×260 / 440×320 dp`，从最接近当前尺寸的档位前进一档。默认 `320×210dp`，下限 `200×120dp`，上限按当前窗口 x/y 动态计算（宽 ≤ 屏宽 − 左边距 − 16dp，高 ≤ 剩余高度 ×0.7），避免拖出屏幕。`width_dp` / `height_dp` 持久化 |
| 背景透明度 | 工具条 `◐` 循环 4 档 drawable alpha `255 / 150 / 70 / 0`，`bg_index` 持久化；底部提示末尾显示 `100% / 60% / 30% / 纯文字` |
| 歌词态（只显示文字） | 第 4 档背景**完全透明**，工具条与提示降到 `alpha 0.15`（仍可点），字幕白字加 8px 四周黑色阴影当描边，保证深色视频画面与浅色界面上都能读；**`◐` 单独补深色圆角小底、始终是唯一清晰可见的锚点**，点一下即可切回有背景档，不会锁死 |
| 锁定 / 隐藏联动 | 锁定态禁拖动、调字号、调外观、改尺寸、隐藏，锁按钮本身始终可点；隐藏态把 LayoutParams 切回 `WRAP_CONTENT`，收成小把手不留大空框，并把背景恢复不透明 |

### 2 · 代码（`core/OverlayController.kt`、`res/layout/overlay_subtitle.xml`、`res/values/strings.xml`）

- 布局从「固定 320dp 宽 + 150dp 高 ScrollView」改为 root / 工具条 / 提示行 `match_parent`、ScrollView `0dp + layout_weight=1`，窗口宽高统一由 `WindowManager.LayoutParams` 决定，`applyWindowSize()` 负责 clamp 与 `updateViewLayout()`。
- 新增 `onResizeTouch()` / `cyclePreset()` / `applyAppearance()` / `cycleAppearance()` / `bgLabel()`；`bgDrawable = view.background?.mutate()`，只改这一个 drawable 的 alpha，不影响其它窗口。
- 底部行新增 `overlay_resize`，工具条新增 `btn_appearance`；`overlay_hint` 精简为一行，`bg_label_*` 用短标签。未新增任何权限，`INTERNET` / `ACCESS_NETWORK_STATE` 仍被 manifest `tools:node="remove"` 移除。
- 构建环境备忘：Gradle 依赖缓存被拆在两个 home（AGP 8.5.2 在 `D:/androiddev/gradle-home`，Kotlin 1.9.23 在默认 `~/.gradle`）；先在线跑一次 `:app:compileDebugKotlin` 补齐 `kotlin-reflect:1.9.23`，之后 `:app:clean :app:assembleDebug --offline` 即可复现。

### 3 · 交付与验证

- `check_project.py` / `check_v2_contract.py` 均 PASS；`compileDebugKotlin` BUILD SUCCESSFUL（3 条既有 warning）；`gradle :app:clean :app:assembleDebug --offline --no-daemon` → `BUILD SUCCESSFUL in 55s`（38 tasks / 37 executed）；`verify_v2_apk.py` → `VERIFY_OK`（484 entries / 36 assets / `BADGING_OK` / `APKSIGNER_OK` / 无网络权限）。
- 当前交付 `D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：`1,050,658,626` B，SHA-256 `d5b7719547c4b61434eaaf39a5dd2695b78bd23be1e9d339a51c0126264030de`；`adb -s 7b4ac943 install -r` → Success，设备端 hash 一致，logcat 无 `FATAL EXCEPTION`。dex 中 `width_dp` / `height_dp` / `bg_index` / `setShadowLayer` / `cycleAppearance` / `cyclePreset` / `applyWindowSize` / `applyAppearance` / `onResizeTouch` 全 FOUND，上一版标识 `e2eFirstMs=` / `forceFlush` / `recognizeSegmented` / `btn_text_translate` 仍在。
- **真机已验证**：冷启动主界面无重叠 / 截断；浮层窗口 `mAttrs=(24,160)(880x578)` 正是默认 `320dp×210dp`，截图可见工具条 6 个按钮、底部提示与右下 `↘`；连点 `◐` 三档后浮层内部采样像素从深色 `(65,66,70)` 变为主界面白底 `(255,255,255)` / `(244,248,246)`，证明背景确实变全透明。用户在真机上手动调过 `font_sp=20`、`width_dp=375` / `height_dp=346`，窗口随之变为 `(1012x952)`，`locked=true` 也生效 —— 尺寸、字号、锁定三条路径都有真机证据。

### 4 · 诚实边界

- 首版装机后发现「纯文字档下白字 `◐` 融进浅色背景、字幕阴影太淡」，已补深色锚点底 + 四周阴影并重新出包装机；**但修复后的纯文字档截图没拿到**：准备复验时设备被切到其它 App（搜索页），为避免干扰用户操作没有继续远程点击，期间 3 次点击未生效、prefs 未被改动。需用户切回后连点 `◐` 到「纯文字」肉眼确认一次。
- `↘` 的拖动与预设点击路径由用户手动操作的 prefs / 窗口尺寸间接证明，未做 adb swipe 定量复测；透明度中间档（60% / 30%）观感未逐一截图评分。
- 纯文字档下工具条与「回到最新」几乎隐形但仍可点击；若找不到恢复入口，认准始终可见的 `◐`。
- 文字翻译与语音字幕各持一份 ONNX 会话，并发 CPU / 内存压制仍未做（沿用上一节边界）。

## 全量测试与缺陷修复（2026-11-14 08:25）

### 1 · 测试范围与结果

| 项 | 结果 |
|---|---|
| `tools/check_project.py` | PASS（结构 / 资源 / 四语向资产 / 运行时零联网） |
| `tools/check_v2_contract.py` | PASS |
| `tests/test_spm_viterbi.py` | `SPM_TEST_PASS`（两套 SPM 与官方 pieces 对齐） |
| `compileDebugKotlin` | BUILD SUCCESSFUL（2 条既有 warning） |
| `clean assembleDebug --offline` | BUILD SUCCESSFUL in 1m 2s（38 tasks / 37 executed） |
| `verify_v2_apk.py` | VERIFY_OK（484 entries / 36 assets / BADGING_OK / APKSIGNER_OK / 无网络权限） |
| 真机（`7b4ac943`） | 冷启动、服务启停、浮层交互全过；`FATAL EXCEPTION` / `ANR` 0 |

### 2 · 修复的缺陷

| # | 问题 | 影响 | 修法 |
|---|---|---|---|
| 1 | `NeuralTranslator.close()` 与 `translate()` 无互斥 | 停止服务 / 退出主界面时若正在推理，`Session.close` 与 `Session.run` 并发 → native 崩溃风险 | `translate` 全程持 `ReentrantLock`；`close()` 先置 `closed`，`tryLock` 失败就交给 `livesub-mt-close` 后台线程排队关闭（不在主线程等最长 12s，避免 ANR） |
| 2 | `ModelAssets.copyAssetToFile` 直接写目标文件 | 服务与「本地文字翻译」同时首次加载同一模型会竞争写；进程被杀留下非空截断文件，之后永远加载失败 | `@Synchronized` + 先写同目录 `.tmp` 再 `renameTo`；rename 失败退化为覆盖拷贝 |
| 3 | `AudioCapture.startReading` 对 `read()<0` 一律 `continue` | 采集失效（DEAD_OBJECT 等）后死循环空转，烧 CPU / 电量 | 区分 0 与负错误码，连续 5 次错误打日志并退出，交回 Service 收尾 |
| 4 | 浮层位置不持久化 | 尺寸 / 字号 / 透明度都记得住，重启后位置却回左上角 | 新增 `pos_x` / `pos_y`，拖动松手与切预设时落盘；`show()` 前按屏幕 clamp |
| 5 | 尺寸上限依赖 `params.x` | 浮层靠右时隐藏再显示，宽度被挤到 200dp 最小值（尺寸缩水） | 上限只跟屏幕有关；仅用户拖右下角时用「剩余屏幕宽度」限制，窗口原地变大不跳位 |
| 6 | 预设档位固定 440dp | 400dp 宽屏上第 3、4 档都被 clamp 成 369dp，点「下一档」没反应，循环卡死 | 档位按 `maxWidthDp` 等比生成（0.65/0.80/0.90/1.0），并跳过 clamp 后与当前相同的档位 |
| 7 | `MainActivity.onCreate` 无条件写「未启动」 | 服务运行中从通知进入，状态卡被覆盖成「未启动」 | 仅在 `!SubtitleState.running.value` 时写 idle |
| 8 | `btnStop` 用 `startService`；`ACTION_RETRY` 可拉起未运行的服务 | 服务没在跑时点停止会凭空创建实例；补译可能留下无通知的常驻服务 | 停止改 `stopService`；`requestRetry()` 在 `!pipelineStarted` 时 `stopSelf()` |
| 9 | `retryTarget` 跨线程读写无同步 | 主线程与翻译协程可见性问题（低概率拿不到补译目标） | 加 `@Volatile` |
| 10 | 过载提示永久挂在状态栏 | 一次过载后状态一直是「过载保留 N 句原文」 | 下一句正常落行即恢复 `baseStatus()`；文案进 `strings.xml` |
| 11 | SRT 序号用 `line.index + 1` | 补译 / 过载跳号时导出文件出现空号 | 按导出顺序 `forEachIndexed` 连续编号 |
| 12 | 「开启悬浮窗权限」已授权时弹的是操作提示 | 文案与场景不匹配 | 新增 `overlay_granted` 专用文案 |

### 3 · 真机验证

- 服务运行中打开主界面，状态卡正确显示「采集中 · 英语 → 中文 · 神经翻译」。
- 纯文字档：解锁后连点 `◐` 三档，浮层背景像素从深色 `(65,66,70)` 变为主界面白底；字幕白字带四周黑晕仍可读；`◐` 的深色圆角底清晰可见（首版白字锚点会融进浅色背景，本轮已修）。
- 预设尺寸：`375×346dp → 240×160dp → 295×210dp`，窗口 `mAttrs` 同步变化，不再卡在同一档。
- 位置持久化：拖动把手 `(24,160) → (322,458)` 落盘；停止服务再启动，窗口仍在 `(322,458)`。
- 测试后已把 `width_dp=375 / height_dp=346 / font_sp=20 / locked=true / bg_index=0` 写回并删除 `pos_x` / `pos_y`，恢复用户原设置。
- 采集侧：`record.read error` 0；`AudioFlinger: RecordThread: buffer overflow` 仅 1 次（服务启停瞬间），worker 正常跟播。

### 4 · 延迟实测（本轮新增端到端数字）

- en→zh 短句（n=4）：`e2eFirstMs` 1577 / 1678 / 2112 / 3132，`asrMs` 1481 / 1536 / 1985 / 3024，`firstPartialMs` 96–142，`sentenceMs` 127–243。
- ja→zh 长句（n=2）：`e2eFirstMs` 4982 / 6954，`asrMs` 4828 / 6816，`firstPartialMs` 138–154。
- **结论**：瓶颈在「语音起点 → ASR 定稿」，不是 MT（MT 首字 100–150ms）。首字延迟随句子长度线性增长，因为要等 VAD 判定句末（0.4s 静音）才定稿。这是当前架构的固有延迟，不是 bug。

### 5 · 未修 / 待权衡

- **首字延迟 1.5–7s**：要压到 1s 级需要「草稿翻译」或原生流式 ASR，属架构改动，本轮未做。
- 浮层旋转后不重新适配（未监听 configuration change）；当前只在下次 `show()` / 交互时按新屏幕 clamp。
- 文字翻译与语音字幕各持一份 ONNX 会话，并发争 CPU / 内存仍未压制。
- 纯文字档下工具条与「回到最新」几乎不可见（设计取舍），恢复入口只有 `◐`。
- 浮层高度上限为屏幕的 70%，不支持全屏高（避免完全遮挡画面）。

### 6 · 交付指纹

`D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：**1,050,788,838 B**，SHA-256 `a5b43a84567d78f917cf5b9449d7a7d223d110d91df9aa4f90599cea0929f1ec`；设备端 hash 一致；`lastUpdateTime=2026-11-14 08:10`。

## 实时性优化三件套：幻觉抑制 + 快速切分 + 草稿译文（2026-11-14 08:55）

### 1 · 先量清楚延迟花在哪

真机 13 句配对（`e2eFirstMs` 与同一段的 `final durMs`）：

| 指标 | 数值 |
|---|---|
| 开口 → 出译文（`e2eFirstMs`） | 1577–5573 ms，几乎等于「这句话本身说了多久」 |
| 最后一个字 → 出译文（`e2eFirstMs − durMs`） | **96–142 ms，P50 118** |
| 句末静音判定（`durMs − segSec`） | 173–820 ms，中位 421 |

结论：翻译链路只有 0.1 秒级，**感知延迟 ≈ 句子长度 + 0.5 秒**。继续优化 MT 没有意义，
只能「提前出字」。据此按 ① 幻觉止血 → ② 切分提速 → ③ 草稿上屏 三步实施。

### 2 · ASR 质量闸门 `AsrQualityGate`

sherpa-onnx 1.12.33 的 `OfflineRecognizerResult` 只暴露 `text / tokens / timestamps / lang`，
**没有 no_speech 概率、没有逐 token logprob，native 层也没有**（javap + strings 核实过），
所以做不了置信度过滤，只能靠文本统计特征。规则按「宁可漏、不可错杀」排序：

| 规则 | 拦什么 | 判定 |
|---|---|---|
| `bracket_annotation` | `(upbeat music)`、`(speaking in Japanese)`、`[applause]` | 以 `(` `[` `（` 开头 |
| `hallucination_phrase` | `Thank you.`、`please subscribe`、`ご視聴ありがとうございました` | 归一化后整句匹配且 ≤4 词 |
| `density` | 一小段音频塞进大量文字 | en >26 字符/秒，ja >20 字/秒 |
| `no_function_word` | `Joku Kara, Bafaron un Merrioto Raimastat` | ≥5 词且高频词命中 0 |
| `proper_noun_burst` | `Meika Minasura Tanga` | 3–4 词、全首字母大写、高频词命中 0 |
| `nonsense` | 大写堆叠 | 高频词 ≤1 且大写占比 ≥60% |
| `repeat_collapsed`（改写不丢句） | `We're not here, but we're not here.` | 周期 3..n/2 重合度 ≥75% 折成一段 |

阈值用真机 13 条真实样本校准；`Three days later.` / `New York City tonight` / `John loves Mary`
这类正常台词不会被误杀（所以 4 词门槛放宽到 5 词，并补了动词变形）。

### 3 · 快速模式（可开关）

- 勾选后单句上限从 12 s 降到 **4 s**（`FAST_MAX_SEGMENT_MS`）；说满 2.5 s 且出现 0.16 s
  能量低谷时提前切句。
- 两个踩过的坑：**Silero 的 `isSpeechDetected()` 在 `minSilenceDuration` 窗口内一直为 true**，
  拿不到 0.16 s 级停顿（第一版实测 `fastCut` 触发 0 次）；改用逐窗 RMS 后又发现
  **影视/纪录片全程铺背景音乐，段内根本没有静音**，最终靠 4 秒硬上限兜底。
- 权衡：会在词间断开（`...to do d|them warm`），译文质量略降。开关在主页「双语显示」下方，
  运行中切换会提示「重新开始字幕后生效」。

### 4 · 草稿译文实时上屏

`AsrEngine` 原有的 200 ms 草稿（最近 2 秒窗口）→ `SubtitleService.maybeTranslateDraft`
按 **800 ms 节流**翻译 → `SubtitleState.setDraftTranslation` → 浮层草稿行（带 `…` 后缀）。
三条闸门保证定稿优先：`latestRequested != activeVersion` 时直接放弃草稿翻译、文本未变不重译、
800 ms 节流。定稿到达时 `setDraft("")` 会一并清空草稿译文，不会挂在新句下面。

真机实测（同一部纪录片，英语→中文，约 60 秒）：

| 路线 | 首字延迟 | 样本 |
|---|---|---|
| 定稿路线 `e2eFirstMs` | 3327 / 3589 / 3992 / 4101 / 4718 / 4817 / 5006 / 5147 ms | n=8 |
| **草稿路线 `draftE2eMs`** | **13 / 322 / 422 / 440 / 652 / 656 / 669 / 782 / 839 / 916 / 954 / 1039 / 1049 / 1206 ms** | n=14 |

中位从约 4.2 秒降到约 0.65 秒。代价：定稿 `sentenceMs` 从 127–243 ms 升到 369–843 ms
（草稿与定稿共用同一个 OrtSession，只能串行），进程 CPU 约 560%、内存约 893 MB。

### 5 · 本版已知边界

- 草稿只覆盖最近 2 秒窗口，文本很短（实测 2–14 字）且会**整体改写**，不是逐词追加；
  浮层草稿行目前与定稿行同色，未做视觉弱化（下轮可用 Spannable 加透明度 / 斜体）。
- `Vittaria, no my koga.`、`KVAT` 这类超短幻觉仍会漏网：词数太少，规则无法与正常短句区分。
- 快速模式的 4 秒硬切会切在词间，译文可能出现半句。
- 草稿路线让 `sentenceMs` 变长约 0.4 秒；若后续反馈发热明显，可把节流放宽到 1200 ms。

### 6 · 交付指纹

`D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：**1,050,717,178 B**，
SHA-256 `d90bfeef39018eec281a62bc3a4ec42835490716a29b3ecc52ec5ebff5a4d536`；
真机 `7b4ac943` 安装 Success，`FATAL` / `ANR` 均为 0。

---

## 屏幕适配 + 词级时间戳 + 草稿降温 + 译名对照（2026-11-14 09:30）

### 1 · 浮层横竖屏适配（真 bug）

**根因**：`AndroidManifest` 里 MainActivity 声明了
`configChanges="orientation|screenSize|screenLayout|keyboardHidden"`，旋转时不重建；
但浮层是独立的 `WindowManager` 窗口，系统**不会**替它重排。`OverlayController` 里既没注册
`ComponentCallbacks` 也没监听配置变化，`dm` 还是构造时缓存的 `val` —— 横屏后仍按竖屏的
像素宽高和坐标摆放。

**修法**：
- `dm` 改成 `get() = context.resources.displayMetrics`，每次读最新。
- `show()` 注册 `context.registerComponentCallbacks(this)`，`dismiss()` 注销。
- `onConfigurationChanged` 里重算尺寸 → clamp 位置 → 重画外观。
- `applyWindowSize` **不再把 clamp 结果回写 `widthDp` / `heightDp`**：横屏时屏幕变矮，
  回写会让用户调好的尺寸永久缩水，转回竖屏也恢复不了。
- `clampPosition` 改用 `params.width/height`（实际显示值），并且：
  窗口整体放得下时不许拖出下边界；放不下时（横屏 / 分屏）至少留 48dp 在屏内。
- 转回竖屏时从 prefs 恢复 `pos_x` / `pos_y`：横屏为了拉回屏内会改 `params.y`，
  不还原的话「竖屏 → 横屏 → 竖屏」会让浮层永久往上挪一截。

**真机三段验证**（设备 `7b4ac943`，设置 369×189 dp）：

| 状态 | 窗口 mAttrs | 说明 |
|---|---|---|
| 竖屏基线 | `(7,722)(1015x520)` | 用户原位置 |
| 横屏（2400×1080） | `(7,560)(1015x520)` | y 被拉回 `1080-520=560`，底边正好贴屏 |
| 转回竖屏 | `(7,722)(1015x520)` | **精确恢复原位置** |

prefs 全程未被污染，仍是 `369×189 @ (7,722)`。

### 2 · 词级时间戳 → SRT 时间轴精度

`sherpa-onnx` 的 `OfflineRecognizerResult` 有 `timestamps`（每个 token 的起点，秒）和
`durations`（各 token 时长），此前代码只取了 `.text`，两者被完全丢弃。

**改动**：`recognize()` 返回 `RecResult(text, speechEndSec)`，
`speechEndSec = 末 token timestamp + 末 token duration`。
`emitSegment` 的 `endMs` 从「识别完成墙钟」改成 `segmentStartMs + speechEndSec`。
超长段切片识别时按 `from / SAMPLE_RATE` 累加偏移，否则只拿到最后一片的时间。

**实证**：修复后日志里 `durMs` 与 `segSec × 1000` 几乎相等：

```
final durMs=2588 segSec=2.588 recMs=407
final durMs=2076 segSec=2.076 recMs=262
final durMs=1308 segSec=1.308 recMs=199
final durMs=3532 segSec=3.532 recMs=626
```

旧实现里 `durMs - segSec` 是 0.17–0.82 秒（正是 `recMs`），导出的 SRT 每句都比真实
语音长一截；现在这段开销不再计入字幕时长。

**必须说清的边界**：时间戳**不能**用来让快速模式「切在词边界」。4 秒硬切发生在音频流上，
那一刻还没有识别结果，拿不到任何 token 位置。能做的只有事后修正时长，改不了切点。
快速模式的破词（`...to do d|them warm`）是这套架构的固有代价，开关默认关。

### 3 · 草稿翻译降温

上一版实测进程峰值 CPU 约 560%，源头是草稿每 800 ms 跑一次完整推理。三处改动：

1. 节流 `DRAFT_TRANSLATE_MS` 800 → **1200 ms**。首字延迟不受影响（首字取决于第一次草稿，
   不是频率），只是后续改写慢一点。
2. `DRAFT_EXTEND_SKIP_CHARS = 6`：草稿是「最近 2 秒窗口」重识别，同一句会被反复吐出来；
   新文本只是在上次译文后面接了几个字时，译文开头不会变，直接跳过这次推理。
3. 草稿的 `isStale` 从写死的 `{ false }` 改成 `{ latestRequested != activeVersion }`：
   定稿一排队就中止正在跑的草稿，既降 CPU 又不再让定稿白等一次解码。

**实测**（60 秒同一片源）：草稿翻译 **8 次**、定稿 5 次；`top` 瞬时 `%CPU` 采样
283% / 81.4% / 3.7%，此前同口径采到 560%。⚠️ 这是瞬时采样不是严格 A/B，
音频内容也不同，只能说明「明显下降」，不能当精确百分比。

### 4 · 译名对照表

小模型逐句独立翻译，没有跨句记忆：同一部片里 Elena 可能第一集「艾琳娜」第三集「埃琳娜」。
sherpa 不暴露 logprob，也没法在解码时 boost 目标 token，所以走**译文后处理**这条确定路径。

**格式**（主界面「译名对照」卡片，逐行）：

```
标准译名=别名1|别名2|原文写法
艾琳娜=Elena|埃琳娜|爱莲娜
```

- `TermGlossary.parse` 忽略格式不对的行；`|`、`,`、`，` 都能当分隔符。
- `apply` 按别名长度**降序**替换：否则同时配了 `Elena` 和 `El` 时，短串会把长串截成
  `艾琳娜ena`。英文别名忽略大小写（模型大小写不稳定），中文按原样匹配。
- 只替换译文，不动原文行 —— 补译要拿原文重跑。
- 服务注册了 `OnSharedPreferenceChangeListener`，运行中改术语表下一句就生效，不必重启字幕。
- 文字翻译走同一张表，两条路径译名一致。

**端到端验证**：同一句输入 `hello`，上一版神经翻译输出「哈啰」；配置
`你好=哈啰|哈罗` 后，结果框显示 **「你好」**。持久化回显、状态文案
「已启用 1 组译名」同时确认。测试后已清空术语表，不留示范数据。

### 5 · 本版已知边界

- 快速模式破词无解（见上）：切分在识别之前发生，时间戳帮不上。
- 术语表是**文本替换**不是术语约束：模型把它翻成完全不同的写法（比如意译成「那姑娘」）
  时匹配不到，仍需用户手动补别名。
- 草稿降温的 CPU 对比是同口径瞬时采样，未做严格 A/B。
- 分屏 / 折叠屏只验证了旋转路径；`displayMetrics` 在分屏下的取值未单独验证。

### 6 · 交付指纹

`D:/AI库/一次归档/浮译-LiveSubtitle-debug.apk`：**1,050,712,606 B**，
SHA-256 `78edd8ca073771180f444cf9d58c7096e44347db5163511ecae626e8b1dada0b`；
`verify_v2_apk.py` VERIFY_OK（484 条目 / 36 资产 / 无网络权限 / 签名通过）；
真机 `7b4ac943` 安装 Success、设备端 hash 一致，`FATAL` / `ANR` 均为 0。
静态自检 `check_project.py` / `check_v2_contract.py` / `test_spm_viterbi.py` 全 PASS。

## 性能重构 + 模型裁剪 + 应用图标（2026-11-14 10:05）

### 1 · 五项改动

| # | 改动 | 位置 | 说明 |
| --- | --- | --- | --- |
| 1 | ASR 首次识别预热 | `AsrEngine` worker 启动处 | 1 秒静音跑一次 `recognize`，把 ORT 计算图编译/内存页开销从第一句挪走；日志 `warmupMs=` |
| 2 | 音频缓冲改环形 | `AsrEngine.FloatRing` | `ArrayList<Float>` → `FloatArray` 环形。旧实现每秒 1.6 万次装箱 + 每帧 `subList().clear()` 搬 6.4 万元素，静音时也在搬 |
| 3 | 浮层渲染缓存 | `OverlayController.update` | 只有 `lines` / `displayMode` 真变化才重建最近 60 行；`partial` 逐 token 刷新不再连带重建历史 |
| 4 | 硬切接缝去重 | `AsrEngine.dropOverlap` | 上一段是强制切开的（`forced=true`）时，下一句开头若与上句尾部重叠 ≥3 字符则剥掉；剥完不足 3 字符整句丢弃（`seam DROP`） |
| 5 | 翻译线程 4→2 | `NeuralTranslator` | 与 ASR 的 2 线程错开，降峰值 CPU |

### 2 · 模型裁剪（en↔ja 两个方向已移除）

- `assets/mt/en_ja`、`assets/mt/ja_en` 移出项目（备份在 `D:/AI库/一次归档/_trimmed_assets/mt/`，未删除）。
- 存量路由从四套缩到**两套**：`mt/en`（en→zh）、`mt/ja`（ja→zh）。
- 主界面「输出语言（中/日/英）」整块移除 —— 目标语只剩中文，`target_zh` / `target_ja` / `target_en` / `target_group` 全部下线；源语音仍保留英语 / 日语两项。
- `ModelAssets.routeDir`、`get_models.py`、`check_project.py`、`check_v2_contract.py`、`verify_v2_apk.py`、`test_spm_viterbi.py` 同步：单测对未随包的 `en_ja` / `ja_en` golden 打 `SKIP`。

### 3 · 应用图标

按用户提供的熊猫抱书图生成：裁掉 4% 白边，图案居中缩到画布的 **62%**，其余铺 `#F2F5FA`（图案边缘中位色，遮罩裁到的都是它）。自适应前景 432×432、`mipmap-mdpi~xxxhdpi` 五档方形 + 圆形、`mipmap-anydpi-v26` 自适应图标。Manifest 补 `android:icon` / `android:roundIcon`。

**踩过的两个坑**（都表现为「图案被裁/没显示全」）：

1. 第一版把图案放到画布的 92% —— 自适应图标只有中间约 **72dp/108dp** 是可见区，超出部分被桌面遮罩直接切掉，书本下沿和头顶都没了。改用 72% 还是顶边，最终定 **62%**，四周留出底色圈才完整。
2. **MIUI 桌面按包名缓存图标位图**，`versionCode` 不变时换图标不刷新；`am force-stop com.miui.home`、`install -r`、甚至重启都不会立即生效。对策是把 `versionCode` 每次都往上加（本版 3 / `0.1.2`），必要时再重启一次桌面进程。这条已写进 `app/build.gradle.kts` 注释。


### 4 · 真机实测（`7b4ac943`）

- `pipeline start src=en dst=zh fastCut=true terms=0`，随后 `warmupMs=187` —— 预热命中。
- 前三句 `recMs` 分别 315 / 347 / 291 ms，**首句不再额外吃亏**（旧版首句常在 600–900ms）。
- 14 句定稿、5 句被闸门拦（`bracket_annotation` 等幻听），全程无 `FATAL EXCEPTION` / `ANR`。
- `top` 瞬时 `%CPU` 十次采样：0 / 3.8 / 37 / 69 / 140 / 181 等，峰值 **181%**（上一轮同口径 283%、更早 560%）。⚠️ 瞬时采样非严格 A/B。
- `seam FIX` 计数 0：本轮片源在硬切处没有出现跨句重复，去重分支未被触发；`dropOverlap` 逻辑用同规则脚本推演过（无重叠→原样返回、`against|against your head`→`your head`、整段重复→DROP），真机触发仍待片源偶遇。

### 5 · 体积与指纹

- APK：`1,050,712,606` B → **`741,007,988` B**（707 MB，减少 295 MB）。
- 当前交付指纹：SHA-256 `d0f2d9fc238670680897db463a710c49c25ef726e10a34e0e820b5eb274c1fec`，`versionCode=3` / `versionName=0.1.2`。
- `verify_v2_apk.py` VERIFY_OK（476 条目 / 22 资产 / 无 INTERNET 权限 / apksigner 通过）。
- `check_project.py` / `check_v2_contract.py` / `test_spm_viterbi.py` 全 PASS。

### 6 · 本版已知边界

- 接缝去重的触发条件是「上一段强制切开**且**下一段开头重复」，安静片源可能整场不触发；它只消除重复，不消除破词。
- 缓冲环形化只降低分配与搬移，识别本身仍是定长窗口推理，段内长停顿的切分精度不变。
- 目标语言已固定中文，若以后要恢复日/英输出，需重新放回 `_trimmed_assets` 两套权重并恢复 UI 与四路由契约。

