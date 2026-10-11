# models/ · 端上抠图模型

> ⚠️ 本目录下的 `*.onnx` **不提交仓库**（见根目录 `.gitignore`）。换机器后执行
> `bash scripts/download-models.sh` 重新获取即可。

## 模型来源

HuggingFace [`Xenova/modnet`](https://huggingface.co/Xenova/modnet) —— MODNet 官方权重的 ONNX 导出，Apache-2.0。
用于 `wx.createInferenceSession` 端上推理，输出 alpha 蒙版（人像 = 1，背景 = 0）。

## 三档对照（`scripts/inspect-model.py` 实测）

| 文件 | 体积 | 算子兼容 | 用途 |
|---|---|---|---|
| **`model_fp16.onnx`** | 12.4 MB | ✅ 全部支持 | **选定档位**（默认） |
| `model.onnx` | 24.7 MB | ✅ 全部支持 | 最稳备选（fp16 出问题时的对照） |
| `model_quantized.onnx` | 6.3 MB | ❌ 含 `ConvInteger` / `DynamicQuantizeLinear` / `MatMulInteger` | **跑不起来**，仅留作对照证据 |

**结论**：微信推理只认 QDQ 静态量化（`FakeQuantize`），不认 ORT 的动态量化算子。
要压体积必须自己做 QDQ 静态量化 + 校准，不能直接拿现成的 int8 包。

## 接口契约（实测确认，不是猜的）

- 输入名 `input`，形状 `[batch, 3, h, w]`（动态轴）
- 输出名 `output`，形状 `[batch, 1, h, w]`
- opset 11
- 预处理对齐官方 `preprocessor_config.json`：短边缩放到 512、宽高取 32 的整数倍、像素归一化 `x / 127.5 - 1`（即 `[0,255] → [-1,1]`）
- 宽高必须能被 **32** 整除（模型内部有 5 次下采样）

## 体积与分发

模型 6~25MB，**远超小程序单包 2MB 上限**，所以：

- 开发期：`node scripts/serve-models.mjs`（本地 HTTP，配合开发者工具「不校验合法域名」）
- 上线后：上传到 CDN/云存储，走 HTTPS，并在 mp 后台把域名加进 **downloadFile 合法域名**
- 运行时流程：`wx.downloadFile` → `FileSystemManager.copyFile` 到 `USER_DATA_PATH` 缓存 → `wx.createInferenceSession` 加载
