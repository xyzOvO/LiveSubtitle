---
language:
- ja
- zh
license: apache-2.0
library_name: onnxruntime
tags:
- translation
- japanese
- chinese
- marian
- opus-mt
- onnx
- asmr
base_model: shun89/opus-mt-ja-zh
pipeline_tag: translation
---

# 拾音 · 日语→中文 离线翻译模型（ONNX fp16, v4）

面向 **日语同人音声（ASMR）** 场景的日语→简体中文翻译模型。
由拾音 App 用于**完全离线**的歌词/台本翻译。

- **基座**：`shun89/opus-mt-ja-zh`（MarianMTModel，**77.5M 参数**）
  > 该仓库是 Helsinki-NLP 原始 OPUS-MT `ja-zh` 的再托管版本
  > （原始仓库 `Helsinki-NLP/opus-mt-ja-zh` 已从 Hugging Face 下架）。
- **语言对**：日语 → 简体中文
- **格式**：ONNX（fp16），用 `onnxruntime` 推理
- **参数量**：77,484,009（约 77.5M）
- **体积**：约 216.9 MB（9 个文件）

## ⚠️ 重要：源文本末尾必须补 `</s>`（EOS，token id = 0）

**这是 Marian / OPUS-MT 系列的使用约定。不补 EOS 会导致输出无限复读。**

对照实测（同一模型、同一输入）：

| 源 token | 输出 |
|---|---|
| `[11506]`（不补 EOS） | `ありがとう ありがとう ありがとう ありがとう…`（无限复读）❌ |
| `[11506, 0]`（补 `</s>`） | `多谢啦` ✅ |

- 用 `transformers` 的 `MarianTokenizer` **会自动补**：
  `tokenizer.encode("ありがとう")` → `[11506, 0]`
- 但**裸用 `sentencepiece` + `onnxruntime` 必须手动补**：

```python
ids = sp_source.encode(text, out_type=int) + [0]   # 0 = </s> = eos_token_id
```

## 版本说明（v4）

本模型为**第 4 版**，重点补充了**全年龄生活系语域**。

| 版本 | 关键变化 |
|---|---|
| v1/v2 | 首版 ASMR 微调；但训练语料 91.5% 为 18+，日常语域几乎零覆盖，导致低频/域外输入**滑向领域词并复读**（如 `おやすみなさい` → `晚安,射出来吧射出来吧…`） |
| v3 | 混入通用日中平行语料（Tatoeba）治「灾难性遗忘」，18+ 占比降至 42.7%，复读基本消除 |
| **v4** | **新增两份全年龄生活系素材**（离家少女、たれうさぎ，共 1018 对），全年龄样本占比 7.3% → 21.4%；通用语料比例 50% → 41.2% |

**实测对照**（40 句留出日常句，从未参与训练）：

| 指标 | 基座 | v3 | **v4** |
|---|---|---|---|
| 日常句污染率 | 0% | 0% | 2% |
| 日常句复读率 | 0% | 2% | **0%** |
| 粤语/英文乱码 | 5/40 | 0/40 | **0/40** |

典型效果：
```
おやすみなさい       → 晚安
おはようございます    → 早上好。        （基座曾输出粤语乱码）
ありがとうございます  → 谢谢你。        （基座曾输出粤语乱码）
おかえりなさい       → 欢迎回来
頑張ってね          → 加油哦
無理しないでね       → 别硬撑哦
```

**跨作品泛化**（15+ 作品「四季之国·春」，完全留出、从未训练）：

| 模型 | 留出集 loss |
|---|---|
| 基座 | 4.8741 |
| **v4** | **4.3221**（降 11.3%）|

大意层面可读（`僕と君が出会ったこの森` → `我和你相遇了这座森林`），符合「能看懂大意」的目标。

**已知局限（如实记录）**：
- 遇到训练集未见的**文体**（如文学朗读、诗）时，仍可能滑向训练集高频词，
  偶发**原文没有的性内容**（详见下方「域外文体」说明）
- `いってきます` 等极低频寒暄句仍可能输出不佳
- 20 句量级的小样本统计有噪声，上述数字宜作趋势而非精确值

### 关于「域外文体」的诚实说明

模型在**训练域内**表现稳定；但在训练集未覆盖的文体上（例如文学化朗读），
可能产出与原文不符的高频领域词。这是**微调导致低频输入退化**的已知现象，
非模型损坏。使用者如遇明显异常输出，属该已知局限。

## 文件说明

> ⚠️ **本仓库仅含 ONNX 格式**，**不含 PyTorch 权重**（无 `pytorch_model.bin` / `model.safetensors`）。
> 因此 `transformers` 的 `MarianMTModel.from_pretrained(repo)` **不能**直接加载模型本体
> （但 `MarianTokenizer.from_pretrained(repo)` 可用）。
> 请用 `onnxruntime`（或 `optimum` 的 `ORTModelForSeq2SeqLM`）加载。

| 文件 | 说明 |
|---|---|
| `encoder_model.onnx` | 编码器（fp16，100.2 MB） |
| `decoder_model.onnx` | 解码器（fp16，112.5 MB，**无 KV cache**） |
| `source.spm` | 日语侧 SentencePiece 分词器（**编码用**） |
| `target.spm` | 中文侧 SentencePiece 分词器（**解码用**） |
| `vocab.json` | 词表（65,001） |
| `tokenizer_config.json` / `special_tokens_map.json` | 分词器配置 |
| `config.json` | 模型配置（`torch_dtype` 记的是**基座 PyTorch 权重**的 dtype = float32；ONNX 导出物为 fp16，两者不矛盾） |

> ⚠️ `source.spm` 与 `target.spm` **两份都要**，语言不同、SHA256 不同，不可互相替代。
> 实测两者 piece↔id 映射一致，故若只需编码也可仅用 `vocab.json`。

## 推理参数

```
decoder_start_token_id = 65000     # 解码起始
eos_token_id           = 0         # </s>，源文本末尾必须补这个
pad_token_id           = 65000
bad_words_ids          = [[65000]]
num_beams              = 4         # 贪心也可用，beam 质量更好但耗时约 ×4
max_length             = 512
```

## 用法示例（裸 ONNX Runtime）

```python
import numpy as np, onnxruntime as ort, sentencepiece as spm

sp_src = spm.SentencePieceProcessor(model_file="source.spm")
sp_tgt = spm.SentencePieceProcessor(model_file="target.spm")
enc = ort.InferenceSession("encoder_model.onnx", providers=["CPUExecutionProvider"])
dec = ort.InferenceSession("decoder_model.onnx", providers=["CPUExecutionProvider"])

def translate(text):
    ids = sp_src.encode(text, out_type=int) + [0]        # ★ 补 </s>
    inp = np.array([ids], dtype=np.int64)
    am = np.ones_like(inp)
    h = enc.run(None, {"input_ids": inp, "attention_mask": am})[0]
    d = np.array([[65000]], dtype=np.int64)
    for _ in range(64):
        lg = dec.run(None, {"input_ids": d, "encoder_hidden_states": h,
                            "encoder_attention_mask": am})[0]
        nxt = int(lg[0, -1].argmax())
        if nxt == 0:
            break
        d = np.concatenate([d, [[nxt]]], axis=1)
    return sp_tgt.decode([int(x) for x in d[0][1:]])
```

## 训练数据说明

**本仓库不提供训练数据。** 训练所用日语音声素材与其中文译文源自第三方汉化组的劳动成果，
**不具备再分发条件**。本仓库仅提供**模型权重**。

## 许可

Apache License 2.0（继承自基座 `shun89/opus-mt-ja-zh`）。
详见 `LICENSE`。
