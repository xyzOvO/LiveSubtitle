# -*- coding: utf-8 -*-
"""用反推的 v4 权重导出 with_past 成对图（dynamo=False + 相对路径，两个坑都规避）"""
import torch
from transformers import MarianConfig, MarianMTModel

print('[1/5] 重建 v4 模型 ...', flush=True)
cfg = MarianConfig.from_pretrained('shun89/opus-mt-ja-zh')
m = MarianMTModel(cfg).eval()
sd = torch.load('ja_decoder_sd.pt', weights_only=True)
missing, unexpected = m.load_state_dict(sd, strict=False)
enc_missing = [k for k in missing if k.startswith('model.encoder.')]
other_missing = [k for k in missing if not k.startswith('model.encoder.')]
print('    decoder 缺失 %d（应为 0，encoder 部分不需要）  unexpected %d'
      % (len(other_missing), len(unexpected)), flush=True)
assert not other_missing and not unexpected
assert torch.equal(m.lm_head.weight, m.model.shared.weight), 'lm_head 与 shared 未绑定'
# tracing 时若参数还带 grad，torch.onnx 会尝试把它当常量插入并报错
m.requires_grad_(False)
L = cfg.decoder_layers; H = cfg.decoder_attention_heads; D = cfg.d_model // H
print('    layers=%d heads=%d dim=%d  vocab=%d' % (L, H, D, cfg.vocab_size), flush=True)

dec, lm_head, bias = m.get_decoder(), m.lm_head, m.final_logits_bias
SFX = ('decoder.key', 'decoder.value', 'encoder.key', 'encoder.value')
pres24 = ['present.%d.%s' % (i, s) for i in range(L) for s in SFX]
pres12 = ['present.%d.%s' % (i, s) for i in range(L) for s in ('decoder.key', 'decoder.value')]
in_past = ['past_key_values.%d.%s' % (i, s) for i in range(L) for s in SFX]
SRC, PAST, OPSET = 6, 4, 14


# 权重必须注册成子模块属性：用全局引用会被 tracing 内联成 Constant 节点，
# 图里就没有 initializer 了，量化器读不到权重、整图量化直接失效（踩过）。
class _Base(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.dec = dec
        self.lm_head = lm_head
        self.register_buffer("bias", bias)


class DecFirst(_Base):
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask):
        o = self.dec(input_ids=input_ids, encoder_hidden_states=encoder_hidden_states,
                     encoder_attention_mask=encoder_attention_mask, past_key_values=None, use_cache=True)
        return ((self.lm_head(o.last_hidden_state) + self.bias),) + tuple(t for l in o.past_key_values for t in l)


class DecWithPast(_Base):
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask, *past_flat):
        past = tuple(tuple(past_flat[i * 4:(i + 1) * 4]) for i in range(L))
        o = self.dec(input_ids=input_ids, encoder_hidden_states=encoder_hidden_states,
                     encoder_attention_mask=encoder_attention_mask, past_key_values=past, use_cache=True)
        return ((self.lm_head(o.last_hidden_state) + self.bias),) + tuple(t for l in o.past_key_values for t in l[:2])


ids = torch.tensor([[cfg.decoder_start_token_id]], dtype=torch.long)
enc_h = torch.randn(1, SRC, cfg.d_model)
enc_m = torch.ones(1, SRC, dtype=torch.long)

print('[2/5] 导出首步图 ...', flush=True)
with torch.no_grad():
    torch.onnx.export(
        DecFirst().eval(), (ids, enc_h, enc_m), 'ja_v4/decoder_model.onnx',
        input_names=['input_ids', 'encoder_hidden_states', 'encoder_attention_mask'],
        output_names=['logits'] + pres24, opset_version=OPSET, do_constant_folding=True,
        dynamo=False, verbose=False,
        dynamic_axes=dict(
            [('input_ids', {0: 'batch', 1: 'dec_seq'}),
             ('encoder_hidden_states', {0: 'batch', 1: 'src_seq'}),
             ('encoder_attention_mask', {0: 'batch', 1: 'src_seq'}),
             ('logits', {0: 'batch', 1: 'dec_seq'})]
            + [('present.%d.decoder.key' % i, {0: 'batch', 2: 'dec_seq'}) for i in range(L)]
            + [('present.%d.decoder.value' % i, {0: 'batch', 2: 'dec_seq'}) for i in range(L)]
            + [('present.%d.encoder.key' % i, {0: 'batch', 2: 'src_seq'}) for i in range(L)]
            + [('present.%d.encoder.value' % i, {0: 'batch', 2: 'src_seq'}) for i in range(L)]),
    )
print('    OK', flush=True)

past = []
for _ in range(L):
    past += [torch.randn(1, H, PAST, D), torch.randn(1, H, PAST, D),
             torch.randn(1, H, SRC, D), torch.randn(1, H, SRC, D)]
print('[3/5] 导出 with_past 图 ...', flush=True)
with torch.no_grad():
    torch.onnx.export(
        DecWithPast().eval(), (ids, enc_h, enc_m) + tuple(past), 'ja_v4/decoder_with_past_model.onnx',
        input_names=['input_ids', 'encoder_hidden_states', 'encoder_attention_mask'] + in_past,
        output_names=['logits'] + pres12, opset_version=OPSET, do_constant_folding=True,
        dynamo=False, verbose=False,
        dynamic_axes=dict(
            [('input_ids', {0: 'batch', 1: 'dec_seq'}),
             ('encoder_hidden_states', {0: 'batch', 1: 'src_seq'}),
             ('encoder_attention_mask', {0: 'batch', 1: 'src_seq'}),
             ('logits', {0: 'batch', 1: 'dec_seq'})]
            + [('past_key_values.%d.decoder.key' % i, {0: 'batch', 2: 'past_seq'}) for i in range(L)]
            + [('past_key_values.%d.decoder.value' % i, {0: 'batch', 2: 'past_seq'}) for i in range(L)]
            + [('past_key_values.%d.encoder.key' % i, {0: 'batch', 2: 'src_seq'}) for i in range(L)]
            + [('past_key_values.%d.encoder.value' % i, {0: 'batch', 2: 'src_seq'}) for i in range(L)]
            + [('present.%d.decoder.key' % i, {0: 'batch', 2: 'total_seq'}) for i in range(L)]
            + [('present.%d.decoder.value' % i, {0: 'batch', 2: 'total_seq'}) for i in range(L)]),
    )
print('    OK', flush=True)

print('[4/5] 量化 ...', flush=True)
import os
from onnxruntime.quantization import QuantType, quantize_dynamic
for src, dst in (('ja_v4/decoder_model.onnx', 'ja_v4/decoder_model_quantized.onnx'),
                 ('ja_v4/decoder_with_past_model.onnx', 'ja_v4/decoder_with_past_model_quantized.onnx')):
    quantize_dynamic(src, dst, weight_type=QuantType.QInt8)
    print('    %-44s %.1f MB' % (os.path.basename(dst), os.path.getsize(dst) / 1048576), flush=True)

print('[5/5] 签名 ...', flush=True)
import onnx
for p in ('ja_v4/decoder_model_quantized.onnx', 'ja_v4/decoder_with_past_model_quantized.onnx'):
    g = onnx.load(p, load_external_data=False).graph
    ins = [i.name for i in g.input]
    outs = [o.name for o in g.output]
    print('    %s  in=%d out=%d past=%d present=%d hidden_in=%s'
          % (os.path.basename(p), len(ins), len(outs),
             sum(n.startswith('past_key_values.') for n in ins),
             sum(n.startswith('present.') for n in outs),
             'encoder_hidden_states' in ins), flush=True)
torch.save(m.state_dict(), 'ja_v4_full_sd.pt')
print('V4_PAIR_OK', flush=True)
