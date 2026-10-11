#!/usr/bin/env bash
# 下载端上抠图所需的 MODNet ONNX 模型（本地开发用，不入 git）
#
# 来源：HuggingFace Xenova/modnet（MODNet 官方权重 onnx 导出，Apache-2.0）
# 用法：bash scripts/download-models.sh [fp16|fp32|int8|all]
#   默认只下 fp16（12.4MB，算子全部命中微信支持列表，是选定档位）
set -euo pipefail

BASE="https://huggingface.co/Xenova/modnet/resolve/main"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models"
mkdir -p "$DIR"

pick="${1:-fp16}"

fetch() {
  local file="$1"
  if [ -s "$DIR/$file" ]; then
    echo "已存在，跳过：$file（$(stat -c%s "$DIR/$file" 2>/dev/null || stat -f%z "$DIR/$file") 字节）"
    return
  fi
  echo "下载 $file ..."
  curl -fL --retry 3 --max-time 900 -o "$DIR/$file" "$BASE/onnx/$file"
  echo "完成：$file（$(stat -c%s "$DIR/$file" 2>/dev/null || stat -f%z "$DIR/$file") 字节）"
}

# 元数据（很小，始终拉取，用于核对预处理参数）
for meta in config.json preprocessor_config.json; do
  [ -s "$DIR/$meta" ] || curl -fsL --retry 3 -o "$DIR/$meta" "$BASE/$meta"
done

case "$pick" in
  fp16) fetch model_fp16.onnx ;;
  fp32) fetch model.onnx ;;
  int8) fetch model_quantized.onnx ;;   # 仅对照，算子不兼容，跑不起来
  all)  fetch model_fp16.onnx; fetch model.onnx; fetch model_quantized.onnx ;;
  *) echo "未知参数：$pick（可选 fp16|fp32|int8|all）"; exit 1 ;;
esac

echo
echo "模型目录：$DIR"
ls -la "$DIR"
echo
echo "体检模型结构：python scripts/inspect-model.py models/model_fp16.onnx"
echo "起本地模型服务：node scripts/serve-models.mjs   # http://127.0.0.1:5178"
