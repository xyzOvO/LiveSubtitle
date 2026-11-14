# Sakura-GalTransl-7B-v3.7 IQ4_XS Android 可行性结论

结论：本轮 APK **不内置** Sakura 权重，也不把 llama.cpp 作为默认实时翻译路径。

## 证据

- 官方模型卡：`license: cc-by-nc-sa-4.0`，即 `CC BY-NC-SA 4.0`；禁止商用，且衍生作品有同协议分享义务。
- 模型定位：7B、日译简中（jp2zh-cn）视觉小说专用；不解决用户要求的默认英语源语音。
- 权重与算力：IQ4_XS 约 4.25GB；官方推荐 6G+ 显存桌面显卡或 MacBook，并说明 IQ4_XS 会比 Q4_K 慢；官方还建议一次翻译 7–10 句以利用上下文。
- llama.cpp 官方有 Android NDK / `examples/llama.android` 路径，但本机 `D:/androiddev/sdk/ndk` 未安装 NDK，且未下载 GGUF、未做手机 CPU token/s benchmark。
- 已连接设备：`7b4ac943`，Xiaomi `2201122C`，Android 10 / API 29 / arm64-v8a，MemTotal 7,826,176 kB，MemAvailable 3,082,036 kB。该设备可做 APK 安装与 logcat smoke，但不能据此推出 7B 模型实时性。

## 决策与理由

1. 不随 APK 分发：许可证是 `CC BY-NC-SA 4.0`，体积约 4.25GB，且只覆盖日语→中文，不符合「默认英语、输出语言可选」的主路径。
2. 不阻塞本轮交付：没有 NDK、没有 GGUF、没有 CPU 实时 benchmark；把它塞进前台服务会带来启动 ANR、内存抖动和不可控延迟。
3. 本轮替代路线：四套轻量 Marian ONNX，用正确 SentencePiece 分词、合并词表 id 映射、`decoder_with_past` KV cache、`setIntraOpNumThreads(4)`，把「快」和「准」建立在可验证的静态契约和 ADB 可观测日志上。
4. 若后续要做实验分支：允许用户自行导入合规 GGUF，经 llama.cpp NDK 在后台跑「批量 ja→zh 精修」，与实时字幕并行但永不作为实时默认路径；必须先补 NDK、许可证审查、真机 token/s 与内存基准。

## 边界

本报告是可行性评估，不声称 Sakura 在 `2201122C` 上实时可用，也不授予商用或再分发许可。
