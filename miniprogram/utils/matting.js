/**
 * 端上人像抠图 + 一键换底（MODNet / ONNX）
 *
 * 依赖能力：
 *   wx.createInferenceSession    基础库 2.30.0+
 *   wx.createOffscreenCanvas     基础库 2.16.1+（type: '2d'）
 *
 * 预处理严格对齐官方 preprocessor_config.json：
 *   短边缩到 512、宽高取 32 的倍数、rescale 1/255、normalize mean=0.5 std=0.5
 *   ⇒ 等价于 x / 127.5 - 1
 */

const specs = require('./specs.js');

// ⚠️ 换模型必须核对：用 Netron 打开 onnx，看 Graph 的 input / output 节点名
let IO = { input: 'input', output: 'output' };

function setIONames(input, output) {
  if (input) IO.input = input;
  if (output) IO.output = output;
}

function getIONames() {
  return { input: IO.input, output: IO.output };
}

const SIZE_DIVISIBILITY = 32;
const DEFAULT_MODEL_FILE = 'modnet-fp32.onnx';
// 仅当调用方既没传 url 也没配置任何来源时的最后兜底（生产用不到，正式版小程序
// 无法访问未备案域名，这里只是保留一个语义上的默认值，便于单测/工具脚本调用）。
const DEFAULT_MODEL_URL = 'https://huggingface.co/Xenova/modnet/resolve/main/onnx/model_fp16.onnx';

/**
 * 【仅本地联调】开发机上跑的模型静态服务（scripts/serve-models.mjs，端口 5178），
 * 填完整地址，例如 'http://192.168.21.11:5178/model.onnx'。
 *   · 真机调试：填电脑的局域网 IP，手机与电脑连同一 Wi-Fi
 *   · 生产环境：**留空**，走服务端下发（后端环境变量 XPHOTO_MODEL_URL）
 *
 * ⚠️ 千万不要把开发机地址写进 MODEL_VARIANTS.url —— 那正是 2026-10-15 线上事故的原因：
 * 发版后真机访问不到 192.168.x.x，报 ERR_CONNECTION_REFUSED、模型准备失败。
 */
const DEV_MODEL_URL = '';

/**
 * 可选模型档位（2026-10 用 scripts/inspect-model.py 实测结论）
 *  base: HuggingFace Xenova/modnet（MODNet 官方权重的 onnx 导出，apache-2.0）
 *
 *  ⚠️ int8「动态量化」版（model_quantized.onnx，6.3MB）**不要用**：
 *     它含 ConvInteger / DynamicQuantizeLinear / MatMulInteger，
 *     这三个算子不在微信「算子支持列表」里，加载或推理会失败。
 *     若确需减小体积，应做 QDQ 静态量化（产出 QuantizeLinear/DequantizeLinear，
 *     对应微信的 FakeQuantize），而不是 ORT 的动态量化。
 */
const MODEL_VARIANTS = [
  {
    key: 'fp32',
    label: 'fp32 · 25MB（真机已验证档 · 默认使用）',
    fileName: 'modnet-fp32.onnx',
    // 下载地址**不写死**：由服务端 /api/photo/specs 下发，见 resolveModelSource()
    url: '',
    opsOk: true,
  },
  {
    key: 'fp16',
    label: 'fp16 · 13MB（⚠️ vivo 实测转换失败，仅对照）',
    fileName: 'modnet-fp16.onnx',
    url: '',
    opsOk: true,
  },
  {
    key: 'int8',
    label: 'int8 · 6.3MB（❌ 算子不支持·仅对照）',
    fileName: 'modnet-int8.onnx',
    url: 'https://huggingface.co/Xenova/modnet/resolve/main/onnx/model_quantized.onnx',
    opsOk: false,
  },
];

// ---------------------------------------------------------------- 模型文件管理

function modelPath(fileName) {
  return `${wx.env.USER_DATA_PATH}/${fileName || DEFAULT_MODEL_FILE}`;
}

/** 本地是否已有模型 */
function modelExists(fileName) {
  const path = modelPath(fileName);
  return new Promise((resolve) => {
    wx.getFileSystemManager().access({
      path,
      success: () => resolve({ exists: true, path, size: fileSize(path) }),
      fail: () => resolve({ exists: false, path, size: 0 }),
    });
  });
}

function fileSize(path) {
  try {
    const st = wx.getFileSystemManager().statSync(path);
    return (st && (st.size || (st.stats && st.stats.size))) || 0;
  } catch (e) {
    return 0;
  }
}

/**
 * 确保模型在本地：没有就下载并持久化。
 * 注意：模型 6~25MB，小程序单包/单分包上限 2MB，**不能打进代码包**，只能运行时下载。
 */
function ensureModel(options) {
  const opts = options || {};
  const fileName = opts.fileName || DEFAULT_MODEL_FILE;
  const url = opts.url || DEFAULT_MODEL_URL;
  const onProgress = opts.onProgress;
  const target = modelPath(fileName);

  return modelExists(fileName).then((r) => {
    if (r.exists && !opts.force) {
      return { path: target, size: r.size, cached: true, costMs: 0 };
    }
    // 模型是唯一一个运行时外部下载：地址必须由后端下发，本地只留联调兜底。
    // 把地址打进日志，是为了出事时一眼看出"到底从哪儿拉的" —— 历史上这里写死过局域网 IP。
    console.log('[photo][外部接口] wx.downloadFile 模型 ' + url
      + '（来源：' + (opts.url ? '后端下发' : '本地默认，仅联调用') + '）');
    return new Promise((resolve, reject) => {
      const t0 = Date.now();
      const task = wx.downloadFile({
        url,
        success: (res) => {
          if (res.statusCode !== 200) {
            console.warn('[photo][外部接口] wx.downloadFile 模型失败 地址=' + url
              + ' HTTP ' + res.statusCode);
            reject(new Error(`模型下载失败 HTTP ${res.statusCode}`));
            return;
          }
          // tempFilePath 会被系统清理，需复制到 USER_DATA_PATH 持久化
          wx.getFileSystemManager().copyFile({
            srcPath: res.tempFilePath,
            destPath: target,
            success: () => {
              const size = fileSize(target);
              console.log('[photo][外部接口] wx.downloadFile 模型 ok 地址=' + url
                + ' 体积=' + Math.round(size / 1024) + 'KB 耗时=' + (Date.now() - t0) + 'ms');
              resolve({ path: target, size, cached: false, costMs: Date.now() - t0 });
            },
            fail: (e) => {
              console.warn('[photo][外部接口] wx.downloadFile 模型保存失败 地址=' + url
                + '：' + JSON.stringify(e));
              reject(new Error('模型保存失败：' + JSON.stringify(e)));
            },
          });
        },
        fail: (e) => {
          console.warn('[photo][外部接口] wx.downloadFile 模型失败 地址=' + url
            + '：' + JSON.stringify(e));
          reject(new Error('模型下载失败：' + JSON.stringify(e)));
        },
      });
      if (onProgress) {
        task.onProgressUpdate((p) =>
          onProgress({ received: p.totalBytesWritten, total: p.totalBytesExpectedToWrite, progress: p.progress })
        );
      }
    });
  });
}

/** 清掉本地模型缓存（换模型/换精度时用） */
function clearModel(fileName) {
  const path = modelPath(fileName);
  return new Promise((resolve) => {
    wx.getFileSystemManager().unlink({ filePath: path, success: () => resolve(true), fail: () => resolve(true) });
  });
}

// ------------------------------------------------------------------ 推理会话

/**
 * 创建推理会话
 * @param {string} path 模型本地路径
 * @param {object} o precisionLevel 0~4 / allowQuantize / allowNPU(iOS) / typicalShape
 */
function createSession(path, o) {
  const opts = o || {};
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    let session;
    try {
      session = wx.createInferenceSession({
        model: path,
        precisionLevel: opts.precisionLevel === undefined ? 2 : opts.precisionLevel,
        allowQuantize: !!opts.allowQuantize,
        allowNPU: !!opts.allowNPU,
        typicalShape: opts.typicalShape || undefined,
      });
    } catch (e) {
      reject(new Error('createInferenceSession 调用失败（基础库需 2.30.0+）：' + (e.errMsg || e.message)));
      return;
    }
    session.onError((err) => reject(new Error('推理会话错误：' + JSON.stringify(err))));
    session.onLoad(() => resolve({ session, loadMs: Date.now() - t0 }));
  });
}

// ------------------------------------------------------------------ 全局共享会话

// 单例：整个小程序生命周期只建一次推理会话（约 25~50MB 常驻内存），
// 页面间共享、切换考试模板 0ms 就绪；模型文件下载依旧只发生一次（有本地缓存）。
// 注意：会话不随页面卸载销毁；小程序被系统回收后下次进入会自动重建。
const SHARED_VARIANT_KEY = 'fp32';
let sharedSession = null;
let sharedSessionPromise = null;

function sharedTypicalShape() {
  // 动态轴模型必须给典型输入尺寸，否则微信转换器可能报 Failed to convert onnx model
  const w = Math.round(384 / 32) * 32;
  const h = Math.round((w * 4) / 3 / 32) * 32;
  const shape = {};
  shape[IO.input] = [1, 3, h, w];
  return shape;
}

/**
 * 获取全局共享推理会话
 * @returns {Promise<{session, cached:boolean, loadMs:number}>}
 */
/**
 * 解析「这次该从哪个地址下载模型」。
 *
 * 优先级：① 服务端下发（specs.modelInfo().url）→ ② 本地联调常量 DEV_MODEL_URL。
 * 两者都没有就抛 NO_MODEL_URL（带 code，调用方可据此提示/重试），
 * **绝不静默退回开发机地址** —— 那正是 2026-10-15 线上事故的根因。
 *
 * 注意每次都重新解析：规格库是异步拉取的，首次进来时可能还没拿到；
 * 服务端地址到达后重试即可成功（见 pages/lab/index.js 的下载重试）。
 * ⚠️ 出片链路（pages/make）已不用端上推理，这里是开发者自检页专用。
 */
function resolveModelSource(variant) {
  const v = variant || MODEL_VARIANTS[0];
  let info = null;
  try {
    info = specs.modelInfo ? specs.modelInfo() : null;
  } catch (e) {
    info = null;
  }
  if (info && info.url) {
    return { fileName: info.fileName || v.fileName, url: info.url, from: 'cloud' };
  }
  if (DEV_MODEL_URL) {
    return { fileName: v.fileName, url: DEV_MODEL_URL, from: 'dev' };
  }
  const err = new Error('模型下载地址未配置：服务端未下发（请检查后端环境变量 XPHOTO_MODEL_URL）');
  err.code = 'NO_MODEL_URL';
  throw err;
}

function getSharedSession() {
  if (sharedSession) {
    return Promise.resolve({ session: sharedSession, cached: true, loadMs: 0 });
  }
  if (sharedSessionPromise) return sharedSessionPromise;
  const variant = MODEL_VARIANTS.find((v) => v.key === SHARED_VARIANT_KEY) || MODEL_VARIANTS[0];
  // 包一层 Promise.resolve().then：解析失败（NO_MODEL_URL）也走 rejected，
  // 而不是同步抛出，调用方统一用 .catch 处理
  const p = Promise.resolve()
    .then(() => resolveModelSource(variant))
    .then((src) => ensureModel({ fileName: src.fileName, url: src.url }))
    .then((r) => createSession(r.path, { precisionLevel: 2, typicalShape: sharedTypicalShape() }))
    .then(({ session, loadMs }) => {
      sharedSession = session;
      sharedSessionPromise = null;
      return { session, cached: false, loadMs };
    });
  p.catch(() => {
    sharedSessionPromise = null; // 失败后允许下次重试
  });
  sharedSessionPromise = p;
  return p;
}

/** 销毁共享会话（目前仅预留：换模型档位/主动释放内存时用） */
function destroySharedSession() {
  if (sharedSession) {
    try {
      sharedSession.destroy && sharedSession.destroy();
    } catch (e) {
      /* ignore */
    }
    sharedSession = null;
  }
  sharedSessionPromise = null;
}

// ------------------------------------------------------------ 图片 → 输入张量

/** 生成符合模型的输入尺寸：短边 shortEdge，宽高均为 32 的倍数 */
function calcInputSize(iw, ih, shortEdge) {
  const scale = (shortEdge || 512) / Math.min(iw, ih);
  let w = Math.round((iw * scale) / SIZE_DIVISIBILITY) * SIZE_DIVISIBILITY;
  let h = Math.round((ih * scale) / SIZE_DIVISIBILITY) * SIZE_DIVISIBILITY;
  w = Math.max(w, SIZE_DIVISIBILITY);
  h = Math.max(h, SIZE_DIVISIBILITY);
  return { width: w, height: h };
}

/** 读图片原始宽高 */
function imageSize(imgPath) {
  return new Promise((resolve, reject) => {
    const canvas = wx.createOffscreenCanvas({ type: '2d', width: 8, height: 8 });
    const img = canvas.createImage();
    img.onload = () => resolve({ width: img.width, height: img.height });
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = imgPath;
  });
}

/**
 * 图片 → NCHW Float32Array（RGB，值域 [-1,1]）
 * @returns {Promise<{data:Float32Array,width:number,height:number,srcWidth:number,srcHeight:number}>}
 */
function imageToTensor(imgPath, shortEdge) {
  return imageSize(imgPath).then(({ width: iw, height: ih }) => {
    const { width, height } = calcInputSize(iw, ih, shortEdge);
    const canvas = wx.createOffscreenCanvas({ type: '2d', width, height });
    const ctx = canvas.getContext('2d');
    const img = canvas.createImage();
    return new Promise((resolve, reject) => {
      img.onload = () => {
        ctx.drawImage(img, 0, 0, width, height);
        const { data } = ctx.getImageData(0, 0, width, height); // RGBA
        const n = width * height;
        const out = new Float32Array(3 * n);
        for (let i = 0, p = 0; i < n; i++, p += 4) {
          out[i] = data[p] / 127.5 - 1;
          out[n + i] = data[p + 1] / 127.5 - 1;
          out[2 * n + i] = data[p + 2] / 127.5 - 1;
        }
        resolve({ data: out, width, height, srcWidth: iw, srcHeight: ih });
      };
      img.onerror = () => reject(new Error('图片解码失败'));
      img.src = imgPath;
    });
  });
}

// -------------------------------------------------------------------- 跑推理

/**
 * 执行推理，返回 0~1 的 alpha 蒙版
 * 自动识别输出是「概率」还是「logits」：若值域明显超出 [0,1] 则套一层 sigmoid
 */
function runMatting(session, tensor) {
  const input = {};
  input[IO.input] = {
    type: 'float32',
    data: tensor.data.buffer,
    shape: [1, 3, tensor.height, tensor.width],
  };
  const t0 = Date.now();
  return session
    .run(input)
    .then((res) => {
      const out = res[IO.output];
      if (!out) {
        throw new Error(`输出节点名不匹配。模型实际输出：${Object.keys(res).join(', ')}`);
      }
      const shape = out.shape || [];
      const raw = new Float32Array(out.data);
      const maskHeight = shape.length >= 4 ? shape[shape.length - 2] : tensor.height;
      const maskWidth = shape.length >= 4 ? shape[shape.length - 1] : tensor.width;
      const total = maskWidth * maskHeight;

      let min = Infinity;
      let max = -Infinity;
      for (let i = 0; i < total; i++) {
        if (raw[i] < min) min = raw[i];
        if (raw[i] > max) max = raw[i];
      }
      const isLogits = min < -0.01 || max > 1.01;
      const mask = new Float32Array(total);
      for (let i = 0; i < total; i++) {
        let v = raw[i];
        if (isLogits) v = 1 / (1 + Math.exp(-v));
        mask[i] = v < 0 ? 0 : v > 1 ? 1 : v;
      }
      return {
        mask,
        maskWidth,
        maskHeight,
        costMs: Date.now() - t0,
        rawMin: min,
        rawMax: max,
        isLogits,
        outShape: shape.join('x'),
      };
    })
    .catch((e) => {
      throw new Error('session.run 失败：' + (e.errMsg || e.message || JSON.stringify(e)));
    });
}

// -------------------------------------------------------------- 蒙版后处理

/** 双线性上采样蒙版（纯 JS，避免依赖 drawImage 画 canvas 的平台差异） */
function resizeMask(mask, mw, mh, tw, th) {
  const out = new Float32Array(tw * th);
  const sx = mw / tw;
  const sy = mh / th;
  for (let y = 0; y < th; y++) {
    const fy = Math.min(Math.max((y + 0.5) * sy - 0.5, 0), mh - 1);
    const y0 = Math.floor(fy);
    const y1 = Math.min(y0 + 1, mh - 1);
    const wy = fy - y0;
    for (let x = 0; x < tw; x++) {
      const fx = Math.min(Math.max((x + 0.5) * sx - 0.5, 0), mw - 1);
      const x0 = Math.floor(fx);
      const x1 = Math.min(x0 + 1, mw - 1);
      const wx = fx - x0;
      const v00 = mask[y0 * mw + x0];
      const v01 = mask[y0 * mw + x1];
      const v10 = mask[y1 * mw + x0];
      const v11 = mask[y1 * mw + x1];
      const top = v00 + (v01 - v00) * wx;
      const bottom = v10 + (v11 - v10) * wx;
      out[y * tw + x] = top + (bottom - top) * wy;
    }
  }
  return out;
}

/** 兼容取 ImageData：优先 createImageData，缺失时退回 getImageData */
function makeImageData(ctx, w, h) {
  if (typeof ctx.createImageData === 'function') {
    try {
      return ctx.createImageData(w, h);
    } catch (e) {
      /* fallthrough */
    }
  }
  return ctx.getImageData(0, 0, w, h);
}

/** 蒙版 → 可视化的灰度图临时文件（黑白，便于肉眼判断边缘质量） */
function maskToPreview(mask, mw, mh) {
  return new Promise((resolve, reject) => {
    const canvas = wx.createOffscreenCanvas({ type: '2d', width: mw, height: mh });
    const ctx = canvas.getContext('2d');
    const imgData = makeImageData(ctx, mw, mh);
    const d = imgData.data;
    for (let i = 0, p = 0; i < mw * mh; i++, p += 4) {
      const v = Math.round(mask[i] * 255);
      d[p] = v;
      d[p + 1] = v;
      d[p + 2] = v;
      d[p + 3] = 255;
    }
    ctx.putImageData(imgData, 0, 0);
    wx.canvasToTempFilePath(
      {
        canvas,
        x: 0,
        y: 0,
        width: mw,
        height: mh,
        destWidth: mw,
        destHeight: mh,
        fileType: 'jpg',
        quality: 0.9,
        success: (r) => resolve(r.tempFilePath),
        fail: (e) => reject(new Error('蒙版导出失败：' + JSON.stringify(e))),
      }
    );
  });
}

// --------------------------------------------------------------- 换底合成

function hexToRgb(hex) {
  const s = String(hex || '#FFFFFF').replace('#', '');
  const v = s.length === 3 ? s.split('').map((c) => c + c).join('') : s;
  return [parseInt(v.slice(0, 2), 16), parseInt(v.slice(2, 4), 16), parseInt(v.slice(4, 6), 16)];
}

/** 按 cover 方式铺满画布（不拉伸变形，超出部分居中裁掉） */
function drawCover(ctx, img, cw, ch) {
  const scale = Math.max(cw / img.width, ch / img.height);
  const w = img.width * scale;
  const h = img.height * scale;
  ctx.drawImage(img, (cw - w) / 2, (ch - h) / 2, w, h);
}

/**
 * 原图 + alpha 蒙版 + 纯色底 → 合成结果
 * @param {string} bgHex 底色，如 '#FFFFFF' / '#438EDB' / '#D9001B'
 * @param {number} quality JPEG 质量（后续做「按目标体积反推质量」时从这里入手）
 */
function composeAndExport(options) {
  const { imgPath, mask, maskWidth, maskHeight, outWidth, outHeight, bgHex, quality } = options;
  return new Promise((resolve, reject) => {
    const canvas = wx.createOffscreenCanvas({ type: '2d', width: outWidth, height: outHeight });
    const ctx = canvas.getContext('2d');
    const img = canvas.createImage();
    img.onload = () => {
      drawCover(ctx, img, outWidth, outHeight);
      const person = ctx.getImageData(0, 0, outWidth, outHeight);
      const alpha = resizeMask(mask, maskWidth, maskHeight, outWidth, outHeight);
      const bg = hexToRgb(bgHex);
      const d = person.data;
      for (let i = 0, p = 0; i < alpha.length; i++, p += 4) {
        const a = alpha[i];
        const ia = 1 - a;
        d[p] = Math.round(d[p] * a + bg[0] * ia);
        d[p + 1] = Math.round(d[p + 1] * a + bg[1] * ia);
        d[p + 2] = Math.round(d[p + 2] * a + bg[2] * ia);
        d[p + 3] = 255;
      }
      ctx.putImageData(person, 0, 0);
      wx.canvasToTempFilePath(
        {
          canvas,
          x: 0,
          y: 0,
          width: outWidth,
          height: outHeight,
          destWidth: outWidth,
          destHeight: outHeight,
          fileType: 'jpg',
          quality: quality || 0.92,
          success: (r) => resolve(r.tempFilePath),
          fail: (e) => reject(new Error('合成导出失败：' + JSON.stringify(e))),
        }
      );
    };
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = imgPath;
  });
}

/** 读取本地文件体积（KB），用于规格校验（教资 ≤200K、国考 20~100KB 等） */
function fileSizeKB(path) {
  return Math.round(fileSize(path) / 1024);
}

module.exports = {
  DEFAULT_MODEL_FILE,
  DEFAULT_MODEL_URL,
  MODEL_VARIANTS,
  setIONames,
  getIONames,
  modelPath,
  modelExists,
  resolveModelSource,
  ensureModel,
  clearModel,
  fileSize,
  fileSizeKB,
  createSession,
  getSharedSession,
  destroySharedSession,
  calcInputSize,
  imageSize,
  imageToTensor,
  runMatting,
  resizeMask,
  maskToPreview,
  makeImageData,
  composeAndExport,
  hexToRgb,
};
