# -*- coding: utf-8 -*-
"""
用法：
    python tools/ja_past_extract.py <原始v4的decoder_model.onnx> [输出.pt]
    python tools/ja_past_export.py

⚠️ 输入必须是**未替换前的原始 v4 图**（fp16、只输出 logits、约 113 MB）。
   仓库 assets 里现在的 decoder_model.onnx 已经是本脚本产出的新图，
   拿它当输入会得到无意义的权重。原始图可从 HF 取：
   https://huggingface.co/ruozhi521/shiyin-mt-ja-zh-onnx
依赖：torch / transformers（导出见 ja_past_export.py，另需 onnxruntime==1.20.1）。

从 v4 的 decoder_model.onnx 反推 Marian decoder 的 PyTorch state_dict。
v4 是微调模型且只有 ONNX，要导 with_past 必须先拿回权重。

映射规则：
  - 名字形如 model.* 的 initializer（LayerNorm / embed_positions / shared）→ 直接用
  - MatMul 的常量 B（Linear weight）→ 从节点输出名解析层路径，取转置
  - final_logits_bias → 直接用
  - lm_head.weight 与 model.shared.weight 绑定
"""
import numpy as np
import onnx
from pathlib import Path
import torch
from onnx import numpy_helper

APP = r"D:\AI库\一次归档\LiveSubtitleAndroid\app\src\main\assets\mt\ja\decoder_model.onnx"
g = onnx.load(APP, load_external_data=False).graph
init = {i.name: i for i in g.initializer}

sd = {}
skipped = []


def arr(name):
    return numpy_helper.to_array(init[name]).astype(np.float32)


# 1) 命名完好的 initializer 直接搬
for name in init:
    if name.startswith("model.") and not name.endswith("_transposed"):
        sd[name] = torch.from_numpy(arr(name))
        continue
    if name == "final_logits_bias":
        sd[name] = torch.from_numpy(arr(name))

# 2) MatMul 的常量 B → Linear weight
lin = 0
for n in g.node:
    if n.op_type != "MatMul":
        continue
    if len(n.input) < 2 or n.input[1] not in init:
        continue
    out = n.output[0]
    if not out.startswith("/model/"):
        skipped.append((out, n.input[1]))
        continue
    segs = [x for x in out.lstrip("/").split("/")]
    if segs and "_output_" in segs[-1]:      # 剥掉 MatMul_output_0 / Add_output_0 这类节点后缀
        segs = segs[:-1]
    key = ".".join(segs) + ".weight"
    w = arr(n.input[1])
    sd[key] = torch.from_numpy(np.ascontiguousarray(w.T))   # ONNX 存 (in,out)，PyTorch 要 (out,in)
    lin += 1

print("MatMul 权重提取 %d 个" % lin)
print("命名搬移 %d 个" % (len(sd) - lin))
print("跳过的 MatMul 输出: %d" % len(skipped))

# 3) lm_head 与 shared 绑定
sd["lm_head.weight"] = sd["model.shared.weight"].clone()
sd["model.decoder.embed_tokens.weight"] = sd["model.shared.weight"].clone()

# 4) 形状核对
print()
print("层数检查：")
for i in range(6):
    keys = [k for k in sd if k.startswith("model.decoder.layers.%d." % i)]
    print("   layer %d: %d 个张量" % (i, len(keys)))

need = []
for i in range(6):
    pre = "model.decoder.layers.%d." % i
    for sub in ("self_attn", "encoder_attn"):
        for pn in ("k_proj", "v_proj", "q_proj", "out_proj"):
            need.append("%s%s.%s.%s" % (pre, sub, pn, sfx) for sfx in ("weight", "bias"))
    for ln in ("self_attn_layer_norm", "encoder_attn_layer_norm", "final_layer_norm"):
        need.append((pre + ln + ".weight", pre + ln + ".bias"))
    for fc in ("fc1", "fc2"):
        need.append((pre + fc + ".weight", pre + fc + ".bias"))
flat = []
for x in need:
    flat.extend(x if isinstance(x, tuple) else (x,))
missing = [k for k in flat if k not in sd]
print()
print("缺失的 decoder 张量: %d" % len(missing))
for k in missing[:10]:
    print("   ", k)

# 5) 关键形状
print()
for k in ("model.decoder.embed_positions.weight", "model.shared.weight",
          "model.decoder.layers.0.self_attn.q_proj.weight",
          "model.decoder.layers.0.fc1.weight", "model.decoder.layers.0.fc2.weight",
          "final_logits_bias", "lm_head.weight"):
    print("   %-48s %s" % (k, tuple(sd[k].shape) if k in sd else "缺失"))

torch.save(sd, OUT_PT)
print()
print("SAVED %s  共 %d 个张量" % (OUT_PT, len(sd)))
