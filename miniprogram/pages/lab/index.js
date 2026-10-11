const matting = require('../../utils/matting');
const face = require('../../utils/face');

const OUT_W = 295; // 标准一寸 / 教资认定推荐尺寸
const OUT_H = 413;
const MAX_LOG = 80;

/** 基础库版本比较：a >= b */
function versionGte(a, b) {
  const pa = String(a || '0').split('.').map(Number);
  const pb = String(b || '0').split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x > y) return true;
    if (x < y) return false;
  }
  return true;
}

Page({
  data: {
    env: { sdkVersion: '-', device: '-', hasInference: false, hasVK: false, hasOffscreen: false },

    variantLabels: [],
    variantIndex: 1,
    modelStateText: '检测中…',
    sessionReady: false,
    loadMs: 0,

    precisionLabels: ['0 · fp16（最快）', '1 · fp16 / 禁 Winograd', '2 · fp16 存储 fp32 计算（默认）', '3 · fp32', '4 · fp32 / 关近似（最准）'],
    precisionIndex: 2,
    shortEdgeLabels: ['256（最快）', '384（快）', '512（官方默认）', '768（慢）'],
    shortEdgeValues: [256, 384, 512, 768],
    shortEdgeIndex: 1,
    bgLabels: ['白底 #FFFFFF', '浅蓝 #438EDB', '红底 #D9001B'],
    bgValues: ['#FFFFFF', '#438EDB', '#D9001B'],
    bgIndex: 0,

    busy: false,
    srcPath: '',
    maskPath: '',
    outPath: '',
    maskShape: '',
    maskRange: '',
    isLogits: false,
    outKB: 0,
    metrics: [],

    facePath: '',
    faceCount: 0,
    faceRows: [],

    logs: [],
  },

  onLoad() {
    const base = wx.getAppBaseInfo ? wx.getAppBaseInfo() : wx.getSystemInfoSync();
    const dev = wx.getDeviceInfo ? wx.getDeviceInfo() : wx.getSystemInfoSync();
    const sdk = base.SDKVersion || base.version || '';
    const env = {
      sdkVersion: sdk || '未知',
      device: `${dev.platform || ''} ${dev.brand || ''} ${dev.model || ''}`.replace(/\s+/g, ' ').trim(),
      isDevtools: dev.platform === 'devtools',
      hasInference: typeof wx.createInferenceSession === 'function',
      hasVK: face.supported(),
      hasOffscreen: typeof wx.createOffscreenCanvas === 'function',
    };
    this.setData({
      env,
      variantLabels: matting.MODEL_VARIANTS.map((v) => v.label),
      variantIndex: 1,
    });
    this.log(`基础库 ${env.sdkVersion}（createInferenceSession 需 2.30.0+：${versionGte(env.sdkVersion, '2.30.0') ? '满足' : '不满足'}）`);
    this.log(`机型 ${env.device}`);
    this.log(`接口：推理=${env.hasInference ? '有' : '无'} / VisionKit=${env.hasVK ? '有' : '无'} / 离屏canvas=${env.hasOffscreen ? '有' : '无'}`);
    if (env.isDevtools) {
      this.log('⚠️ 开发者工具模拟器不支持 createInferenceSession，请点「预览」扫码用真机跑');
    }
    this.refreshModelState();
  },

  onUnload() {
    this.destroySession();
  },

  // ------------------------------------------------------------------ 工具

  log(msg) {
    const logs = this.data.logs.concat(`[${new Date().toTimeString().slice(0, 8)}] ${msg}`);
    this.setData({ logs: logs.slice(-MAX_LOG) });
    console.log(msg);
  },

  toast(title, icon) {
    wx.showToast({ title, icon: icon || 'none' });
  },

  destroySession() {
    if (this.session) {
      try {
        this.session.destroy();
      } catch (e) {
        /* ignore */
      }
      this.session = null;
    }
    this.setData({ sessionReady: false });
  },

  currentVariant() {
    return matting.MODEL_VARIANTS[this.data.variantIndex] || matting.MODEL_VARIANTS[0];
  },

  refreshModelState() {
    const v = this.currentVariant();
    return matting.modelExists(v.fileName).then((r) => {
      this.setData({
        modelStateText: r.exists ? `已缓存 ${(r.size / 1048576).toFixed(1)}MB` : '未下载',
      });
      return r;
    });
  },

  // -------------------------------------------------------- 模型准备 / 清理

  onVariantChange(e) {
    const idx = Number(e.detail.value);
    this.destroySession();
    this.setData({ variantIndex: idx });
    const v = matting.MODEL_VARIANTS[idx];
    this.log(`切换模型档位 → ${v.label}`);
    if (!v.opsOk) {
      this.log('⚠️ 该档位含微信不支持算子（ConvInteger / DynamicQuantizeLinear / MatMulInteger），预期加载或推理失败，仅作对照。');
    }
    this.refreshModelState();
  },

  onPrepareModel() {
    if (this.data.busy) return;
    const v = this.currentVariant();
    this.setData({ busy: true, modelStateText: '下载中… 0%' });

    matting
      .ensureModel({
        fileName: v.fileName,
        url: v.url,
        onProgress: (p) => this.setData({ modelStateText: `下载中… ${p.progress}%` }),
      })
      .then((r) => {
        this.downloadMs = r.costMs;
        this.log(`模型就绪：${(r.size / 1048576).toFixed(1)}MB，${r.cached ? '命中本地缓存' : `下载耗时 ${r.costMs}ms`}`);
        this.setData({ modelStateText: `已缓存 ${(r.size / 1048576).toFixed(1)}MB` });

        // 动态轴模型传典型输入尺寸，帮助运行时选 Kernel
        const w = Math.round(this.data.shortEdgeValues[this.data.shortEdgeIndex] / 32) * 32;
        const h = Math.round((w * 4) / 3 / 32) * 32;
        const typicalShape = {};
        typicalShape[matting.getIONames().input] = [1, 3, h, w];

        return matting.createSession(r.path, {
          precisionLevel: this.data.precisionIndex,
          typicalShape,
        });
      })
      .then(({ session, loadMs }) => {
        this.session = session;
        this.setData({ sessionReady: true, loadMs });
        this.log(`推理会话创建成功，加载耗时 ${loadMs}ms（precisionLevel=${this.data.precisionIndex}）`);
        this.toast('模型已就绪');
      })
      .catch((err) => {
        const msg = err && (err.message || err.errMsg || JSON.stringify(err));
        this.log(`❌ 模型准备失败：${msg}`);
        this.toast('模型准备失败，见日志');
      })
      .then(() => this.setData({ busy: false }));
  },

  onClearModel() {
    if (this.data.busy) return;
    this.destroySession();
    const v = this.currentVariant();
    matting.clearModel(v.fileName).then(() => {
      this.log(`已清除本地模型缓存：${v.fileName}`);
      this.refreshModelState();
    });
  },

  // -------------------------------------------------------------- 主链路

  onPickAndRun() {
    if (this.data.busy) return;
    if (!this.data.sessionReady) {
      this.toast('请先「下载并加载模型」');
      return;
    }
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      sizeType: ['original'],
      success: (res) => {
        const f = res.tempFiles && res.tempFiles[0];
        if (!f) return;
        this.setData({ srcPath: f.tempFilePath });
        this.runPipeline(f.tempFilePath);
      },
    });
  },

  runPipeline(imgPath) {
    const shortEdge = this.data.shortEdgeValues[this.data.shortEdgeIndex];
    const bgHex = this.data.bgValues[this.data.bgIndex];
    const metrics = [];
    this.setData({ busy: true, metrics: [], maskPath: '', outPath: '', maskShape: '', maskRange: '', outKB: 0 });
    this.log(`开始：短边 ${shortEdge}，底色 ${bgHex}，precisionLevel ${this.data.precisionIndex}`);

    const tAll = Date.now();
    let t0 = Date.now();

    matting
      .imageToTensor(imgPath, shortEdge)
      .then((tensor) => {
        const prepMs = Date.now() - t0;
        metrics.push({ k: '前处理（解码+缩放+归一化）', v: `${prepMs} ms` });
        this.log(`输入张量 ${tensor.width}×${tensor.height}（原图 ${tensor.srcWidth}×${tensor.srcHeight}），前处理 ${prepMs}ms`);
        t0 = Date.now();
        return matting.runMatting(this.session, tensor).then((r) => Object.assign(r, { tensor }));
      })
      .then((r) => {
        metrics.push({ k: '端上推理', v: `${r.costMs} ms` });
        this.setData({
          maskShape: `${r.maskWidth}×${r.maskHeight}`,
          maskRange: `${r.rawMin.toFixed(3)}, ${r.rawMax.toFixed(3)}`,
          isLogits: r.isLogits,
        });
        this.log(`推理完成 ${r.costMs}ms，输出 ${r.outShape}，值域 [${r.rawMin.toFixed(3)}, ${r.rawMax.toFixed(3)}]${r.isLogits ? '（判定为 logits，已 sigmoid）' : ''}`);
        t0 = Date.now();
        return matting.maskToPreview(r.mask, r.maskWidth, r.maskHeight).then((maskPath) => {
          metrics.push({ k: '蒙版导出', v: `${Date.now() - t0} ms` });
          this.setData({ maskPath });
          return r;
        });
      })
      .then((r) => {
        t0 = Date.now();
        return matting
          .composeAndExport({
            imgPath,
            mask: r.mask,
            maskWidth: r.maskWidth,
            maskHeight: r.maskHeight,
            outWidth: OUT_W,
            outHeight: OUT_H,
            bgHex,
            quality: 0.92,
          })
          .then((outPath) => {
            metrics.push({ k: '上采样 + 换底合成 + 导出', v: `${Date.now() - t0} ms` });
            const kb = matting.fileSizeKB(outPath);
            metrics.push({ k: '导出体积', v: `${kb} KB（${OUT_W}×${OUT_H}）` });
            metrics.push({ k: '总计', v: `${Date.now() - tAll} ms` });
            this.setData({ outPath, outKB: kb, metrics });
            this.log(`合成完成，导出 ${kb}KB，端上全链路总计 ${Date.now() - tAll}ms`);
            this.toast('完成');
          });
      })
      .catch((err) => {
        const msg = err && (err.message || err.errMsg || JSON.stringify(err));
        this.log(`❌ 链路失败：${msg}`);
        this.toast('失败，见日志');
      })
      .then(() => this.setData({ busy: false }));
  },

  // -------------------------------------------------- VisionKit 人脸检测

  onTestFace() {
    if (this.data.busy) return;
    if (!this.data.env.hasVK) {
      this.toast('当前基础库不支持 VisionKit');
      return;
    }
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      sizeType: ['original'],
      success: (res) => {
        const f = res.tempFiles && res.tempFiles[0];
        if (!f) return;
        this.runFaceDetect(f.tempFilePath);
      },
    });
  },

  runFaceDetect(imgPath) {
    this.setData({ busy: true, facePath: '', faceRows: [] });
    this.log('开始 VisionKit 人脸检测…');
    const t0 = Date.now();
    matting
      .imageSize(imgPath)
      .then(({ width, height }) => {
        // 限制送检尺寸，兼顾速度与关键点精度
        const cap = 1080;
        const scale = Math.min(1, cap / Math.max(width, height));
        const w = Math.max(32, Math.round(width * scale));
        const h = Math.max(32, Math.round(height * scale));
        return face.detectFace(imgPath, w, h).then((r) => Object.assign(r, { w, h }));
      })
      .then((r) => {
        const f0 = r.faces[0];
        const rows = [
          { k: '检测耗时', v: `${r.costMs} ms` },
          { k: '送检尺寸', v: `${r.w} × ${r.h}` },
          { k: '人脸框（归一化）', v: `x${f0.origin.x.toFixed(3)} y${f0.origin.y.toFixed(3)} w${f0.size.width.toFixed(3)} h${f0.size.height.toFixed(3)}` },
          { k: '人脸框（像素）', v: `${Math.round(f0.left)}, ${Math.round(f0.top)}, ${Math.round(f0.boxWidth)}×${Math.round(f0.boxHeight)}` },
          { k: '关键点数', v: `${(f0.points || []).length}` },
          { k: '旋转角', v: f0.angle ? `${f0.angle.yaw.toFixed(2)}, ${f0.angle.pitch.toFixed(2)}, ${f0.angle.roll.toFixed(2)}` : '—' },
        ];
        this.setData({ faceCount: r.faces.length, faceRows: rows });
        this.log(`检出 ${r.faces.length} 张人脸，耗时 ${r.costMs}ms（总 ${Date.now() - t0}ms）`);
        return this.renderFaceBox(imgPath, r.w, r.h, r.faces).then((facePath) => this.setData({ facePath }));
      })
      .catch((err) => {
        const msg = err && (err.message || err.errMsg || JSON.stringify(err));
        this.log(`❌ 人脸检测失败：${msg}`);
        this.toast('检测失败，见日志');
      })
      .then(() => this.setData({ busy: false }));
  },

  /** 把检测框画到图上导出，肉眼确认是否准确 */
  renderFaceBox(imgPath, w, h, faces) {
    return new Promise((resolve) => {
      const canvas = wx.createOffscreenCanvas({ type: '2d', width: w, height: h });
      const ctx = canvas.getContext('2d');
      const img = canvas.createImage();
      img.onload = () => {
        ctx.drawImage(img, 0, 0, w, h);
        ctx.strokeStyle = '#ff3b30';
        ctx.lineWidth = Math.max(2, Math.round(w / 200));
        faces.forEach((f) => ctx.strokeRect(f.left, f.top, f.boxWidth, f.boxHeight));
        wx.canvasToTempFilePath({
          canvas,
          x: 0,
          y: 0,
          width: w,
          height: h,
          destWidth: w,
          destHeight: h,
          fileType: 'jpg',
          quality: 0.9,
          success: (r) => resolve(r.tempFilePath),
          fail: () => resolve(''),
        });
      };
      img.onerror = () => resolve('');
      img.src = imgPath;
    });
  },

  onPreview(e) {
    const src = e.currentTarget.dataset.src;
    if (src) wx.previewImage({ urls: [src] });
  },
});
