# -*- coding: utf-8 -*-
"""
MODNet ONNX 模型体检：输入输出 / 算子清单 / 微信小程序算子支持比对
用法: python inspect-model.py ../models/model_quantized.onnx
"""
import sys
from collections import Counter

import onnx
from onnx import TensorProto

DTYPE = {v: k for k, v in TensorProto.DataType.items()}
DTYPE_SHORT = {
    1: "float32", 2: "uint8", 3: "int8", 4: "uint16", 5: "int16", 6: "int32",
    7: "int64", 9: "bool", 10: "float16", 11: "double", 12: "uint32", 13: "uint64",
}

# 微信小程序 AI 推理「算子支持列表」
# https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/inference/supports.html
WX_OPS = {
    "Activation", "ArgMax", "ArgMin", "BatchNorm", "Bias", "Binary", "Bucketize",
    "Cast", "Concat", "Const", "ConstOfShape", "Conv1D", "Conv1DTranspose",
    "Conv2D", "Conv2DTranspose", "Conv3D", "Conv3DTranspose", "Crop",
    "CropAndResize", "CumSum", "DepthToSpace", "Dropout", "ElementWise", "Expand",
    "FakeQuantize", "Flatten", "FullyConnected", "Gather", "GatherND", "Gemm",
    "GlobalPooling", "GroupNorm", "Gru", "InstanceNorm", "LayerNorm", "LpNorm",
    "Lrn", "Lstm", "MatMul", "NMS", "Normalize", "OneHot", "Pad", "Permute",
    "Pooling1D", "Pooling2D", "Pooling3D", "PriorBox", "Range", "Reduce",
    "Reshape", "Resize2D", "Rnn", "Scale", "ScatterND", "Shape", "ShuffleChannel",
    "SpaceToDepth", "Split", "Slice", "Softmax", "Squeeze", "Tile", "TopK",
    "Unary", "Unsqueeze", "Where",
}

# ONNX 算子名 → 微信算子名
ONNX2WX = {
    "Conv": "Conv2D", "ConvTranspose": "Conv2DTranspose",
    "BatchNormalization": "BatchNorm", "InstanceNormalization": "InstanceNorm",
    "GroupNormalization": "GroupNorm", "LayerNormalization": "LayerNorm",
    "Relu": "Activation", "PRelu": "Activation", "LeakyRelu": "Activation",
    "Sigmoid": "Activation", "Tanh": "Activation", "HardSigmoid": "Activation",
    "HardSwish": "Activation", "Clip": "Activation", "Elu": "Activation",
    "Selu": "Activation", "Softplus": "Activation", "Softsign": "Activation",
    "ThresholdedRelu": "Activation", "Erf": "Unary",
    "Add": "Binary", "Sub": "Binary", "Mul": "Binary", "Div": "Binary",
    "Pow": "Binary", "Max": "Binary", "Min": "Binary", "Mean": "Binary",
    "Equal": "Binary", "Greater": "Binary", "Less": "Binary",
    "Neg": "Unary", "Abs": "Unary", "Ceil": "Unary", "Floor": "Unary",
    "Sqrt": "Unary", "Exp": "Unary", "Log": "Unary", "Reciprocal": "Unary",
    "Cos": "Unary", "Sin": "Unary", "Tan": "Unary",
    "MaxPool": "Pooling2D", "AveragePool": "Pooling2D",
    "GlobalAveragePool": "GlobalPooling", "GlobalMaxPool": "GlobalPooling",
    "Resize": "Resize2D", "Upsample": "Resize2D",
    "Transpose": "Permute", "Constant": "Const", "ConstantOfShape": "ConstOfShape",
    "ReduceMean": "Reduce", "ReduceSum": "Reduce", "ReduceMax": "Reduce",
    "ReduceMin": "Reduce", "ReduceProd": "Reduce",
    "MatMul": "MatMul", "Gemm": "FullyConnected",
    "Concat": "Concat", "Split": "Split", "Slice": "Slice", "Squeeze": "Squeeze",
    "Unsqueeze": "Unsqueeze", "Shape": "Shape", "Reshape": "Reshape",
    "Cast": "Cast", "Pad": "Pad", "Where": "Where", "Softmax": "Softmax",
    "Flatten": "Flatten", "DepthToSpace": "DepthToSpace", "Tile": "Tile",
    "Expand": "Expand", "Range": "Range", "TopK": "TopK", "Gather": "Gather",
    "GatherND": "GatherND", "ScatterND": "ScatterND", "CumSum": "CumSum",
    "Dropout": "Dropout", "ArgMax": "ArgMax", "ArgMin": "ArgMin",
    "Div_": "Binary", "Not": "Unary",
}


def shape_of(t):
    return [d.dim_value if d.HasField("dim_value") else (d.dim_param or "?") for d in t.type.tensor_type.shape.dim]


def main(path):
    print("=" * 74)
    print("模型:", path)
    print("=" * 74)
    m = onnx.load(path)
    g = m.graph

    print("\n[1] graph 输入")
    if not g.input:
        print("    (无，全部已被 initializer 吸收)")
    for t in g.input:
        if t.name in {i.name for i in g.initializer}:
            continue
        print(f"    name={t.name!r}  shape={shape_of(t)}  dtype={DTYPE_SHORT.get(t.type.tensor_type.elem_type)}")

    print("\n[2] graph 输出")
    for t in g.output:
        print(f"    name={t.name!r}  shape={shape_of(t)}  dtype={DTYPE_SHORT.get(t.type.tensor_type.elem_type)}")

    print("\n[3] opset")
    for op in m.opset_import:
        print(f"    domain={op.domain or 'ai.onnx'}  version={op.version}")

    print("\n[4] 算子清单（按出现次数）")
    cnt = Counter(n.op_type for n in g.node)
    for op, c in cnt.most_common():
        wx = ONNX2WX.get(op, ONNX2WX.get(op.rstrip("_")))
        mark = "支持" if wx in WX_OPS else "未知"
        print(f"    {op:<22} x{c:<4} -> 微信 {str(wx):<18} [{mark}]")

    print("\n[5] 结论")
    unknown = sorted({op for op in cnt if ONNX2WX.get(op) not in WX_OPS})
    if unknown:
        print("    ⚠️ 以下算子无法映射到微信支持列表，需实测确认：")
        for op in unknown:
            print("       -", op, f"x{cnt[op]}")
    else:
        print("    ✅ 全部算子都能映射到微信官方支持列表")

    # 权重精度：量化模型是 int8，weixin allowQuantize 需配合
    dt = Counter(i.data_type for i in g.initializer)
    print("\n[6] 权重精度")
    for k, v in dt.items():
        print(f"    {DTYPE_SHORT.get(k, k):<10} x{v}")
    q = [i for i in g.initializer if i.data_type in (2, 3)]
    if q:
        print(f"    → 含 {len(q)} 个 int8/uint8 量化权重（这是已量化模型，加载时无需再开 allowQuantize）")
    dynamic = any(
        isinstance(d, str) or (hasattr(d, "dim_param") and d.dim_param)
        for t in g.input for d in [x for x in shape_of(t)]
    )
    print(f"\n[7] 动态轴: {'是（typicalShape 需按实际推理尺寸传）' if dynamic else '否（固定输入尺寸）'}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
        print()
