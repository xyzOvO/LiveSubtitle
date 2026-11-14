# 第三方模型与素材说明

本仓库的**源代码**以 [MIT](LICENSE) 发布。`app/src/main/assets/` 下随 APK 分发的模型权重与词表**不属于**本仓库，
版权归各自上游项目所有，遵循其各自的许可证；再分发（含打包进 APK 分发）前请自行核对上游条款。

| 资产 | 上游 | 用途 |
|---|---|---|
| `asr/sherpa-onnx-whisper-tiny.en` | OpenAI Whisper tiny.en，经 k2-fsa/sherpa-onnx 导出为 ONNX（int8） | 英语语音识别 |
| `asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01` | ReazonSpeech（Reazon Holdings）日語 zipformer，经 sherpa-onnx 导出（int8） | 日语语音识别 |
| `asr/silero_vad.onnx` | Silero VAD | 语音活动检测 |
| `mt/en` | `Xenova/opus-mt-en-zh`（Marian NMT，量化 ONNX） | 英语 → 中文 |
| `mt/ja` | `ruozhi521/shiyin-mt-ja-zh-onnx`（Marian NMT） | 日语 → 中文 |

各目录内若带有 `README.md` / `LICENSE`（例如 `mt/ja/`、`mt/en/`），那是上游原文，保留未改动。

`sherpa-onnx` 运行时以 AAR 形式引入（`com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.33`），`onnxruntime-android:1.29.0`
为 ONNX Runtime 官方 Android 包；两者的许可证见各自 Maven 坐标对应的仓库。

应用图标为作者自绘（蜡笔风熊猫抱书图），随本仓库以 MIT 发布。

---

以上仅为归属说明，不构成法律意见；如需商用分发，请自行完成许可证合规审核。
