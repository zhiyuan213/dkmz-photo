/**
 * 证件照制作流水线（阶段 1 · 免费链路）
 *
 * 选照片 → 人脸检测定位 → 按考试规格构图裁剪 → 端上抠图 → 换底合成
 *        → 叠加预览水印 → 按「目标体积」二分法调 JPEG 质量导出
 *
 * 依赖：
 *  - utils/face.js   VisionKit 静态图人脸检测 + calcCropBox 构图
 *  - utils/matting.js MODNet 推理会话（session 由调用方提前建好传入）
 *
 * 隐私：原图全程不出手机，只在端上处理。
 */

const matting = require('./matting');
const face = require('./face');

/** 人脸检测送检上限（最长边），兼顾速度与精度；坐标按比例映射回原图 */
const FACE_DETECT_CAP = 1280;

/** 裁剪导出的中间图质量 */
const CROP_QUALITY = 0.95;

/** 质量二分范围与迭代上限 */
const Q_MIN = 0.35;
const Q_MAX = 0.95;
const Q_ITER = 7;

// ------------------------------------------------------------- 人脸与构图

/**
 * 检测人脸，返回归一化映射回「原图像素坐标」的人脸框
 * VisionKit 偶发不回流结果（检测超时）：自动用小模型档重试一次，仍失败则
 * 返回 face:null（上层走兜底构图），**不把整单制作判失败**。
 * @returns {Promise<{face:Object|null, origW:number, origH:number, costMs:number, keypoints:number, reason?:string}>}
 */
function detectFaceOriginal(imgPath) {
  return matting.imageSize(imgPath).then(({ width: ow, height: oh }) => {
    const maxSide = Math.max(ow, oh);
    if (!face.supported()) {
      return { face: null, origW: ow, origH: oh, costMs: 0, keypoints: 0, reason: 'VisionKit 不支持' };
    }
    const attempts = [
      // 正常检测 ~130ms 量级，3s 没结果基本就是照片里没有人，
      // 快速超时换下一档，避免无脸照片干等 6s×2
      { scale: Math.min(1, FACE_DETECT_CAP / maxSide), modelMode: 1, timeoutMs: 3000 }, // 标准档
      { scale: Math.min(1, 720 / maxSide), modelMode: 0, timeoutMs: 3000 }, // 兜底：更小尺寸 + 小模型
    ];
    let state = { face: null, origW: ow, origH: oh, costMs: 0, keypoints: 0, reason: '未检出人脸' };
    let p = Promise.resolve(state);
    attempts.forEach((a) => {
      p = p.then((cur) => {
        if (cur.face) return cur;
        const w = Math.max(32, Math.round(ow * a.scale));
        const h = Math.max(32, Math.round(oh * a.scale));
        return face
          .detectFace(imgPath, w, h, { modelMode: a.modelMode, timeoutMs: a.timeoutMs })
          .then((r) => {
            const f0 = (r.faces || [])[0] || null;
            const costMs = cur.costMs + (r.costMs || 0);
            if (!f0) {
              return Object.assign(cur, { costMs, reason: '未检出人脸' });
            }
            return {
              face: {
                left: f0.left / a.scale,
                top: f0.top / a.scale,
                boxWidth: f0.boxWidth / a.scale,
                boxHeight: f0.boxHeight / a.scale,
              },
              origW: ow,
              origH: oh,
              costMs,
              keypoints: (f0.points || []).length,
              reason: null,
            };
          })
          .catch(() => Object.assign(cur, { reason: '检测超时，已改用默认构图' }));
      });
    });
    return p;
  });
}

/** 无人脸时的兜底构图：假设人脸在画面上部中间（常见自拍构图） */
function fallbackCropBox(imgWidth, imgHeight, ratio, faceHeightRatio) {
  const faceH = Math.min(imgWidth, imgHeight) * 0.35;
  const fakeFace = {
    left: imgWidth / 2 - faceH * 0.35,
    top: imgHeight * 0.08,
    boxWidth: faceH * 0.7,
    boxHeight: faceH,
  };
  return face.calcCropBox(fakeFace, imgWidth, imgHeight, ratio, faceHeightRatio);
}

// ----------------------------------------------------------- 裁剪与缩放

/**
 * 从原图按 box 裁剪并缩放到 outW×outH，导出临时 jpg
 * box 只允许顶部越出原图（头顶留白不足等场景）：越界区域铺白色底，
 * 抠图后 alpha=0 会被目标底色覆盖，视觉无感。
 * 横向/底部不越界（calcCropBox 已保证）——横向造像素（边缘列拉伸）
 * 会产出与人身断开的悬空色块，已废弃。
 */
function cropAndScale(imgPath, box, outW, outH) {
  return new Promise((resolve, reject) => {
    const canvas = wx.createOffscreenCanvas({ type: '2d', width: outW, height: outH });
    const ctx = canvas.getContext('2d');
    const img = canvas.createImage();
    img.onload = () => {
      // 1) 先铺白底（顶部越界补底；同时兜住 drawImage 未覆盖的区域）
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(0, 0, outW, outH);
      // 2) 手动求源图与裁剪框交集，按比例映射到目标画布
      //    （不直接把越界 rect 塞给 drawImage，规避端上实现的裁剪行为差异）
      const sx = Math.max(0, box.left);
      const sy = Math.max(0, box.top);
      const ex = Math.min(img.width, box.left + box.width);
      const ey = Math.min(img.height, box.top + box.height);
      if (ex > sx && ey > sy) {
        const scaleX = outW / box.width;
        const scaleY = outH / box.height;
        const dx = (sx - box.left) * scaleX;
        const dy = (sy - box.top) * scaleY;
        const dw = (ex - sx) * scaleX;
        const dh = (ey - sy) * scaleY;
        ctx.drawImage(img, sx, sy, ex - sx, ey - sy, dx, dy, dw, dh);
      }
      wx.canvasToTempFilePath({
        canvas,
        x: 0,
        y: 0,
        width: outW,
        height: outH,
        destWidth: outW,
        destHeight: outH,
        fileType: 'jpg',
        quality: CROP_QUALITY,
        success: (r) => resolve(r.tempFilePath),
        fail: (e) => reject(new Error('裁剪导出失败：' + JSON.stringify(e))),
      });
    };
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = imgPath;
  });
}

// ----------------------------------------------------------------- 水印

/** 平铺旋转水印（预览版；付费高清版不叠加） */
function drawWatermark(ctx, w, h, text) {
  const t = text || '登科喵证件照 · 预览';
  const step = Math.max(w, h) / 2.6;
  ctx.save();
  ctx.translate(w / 2, h / 2);
  ctx.rotate((-30 * Math.PI) / 180);
  ctx.font = `bold ${Math.max(12, Math.round(w / 16))}px sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = 'rgba(128, 128, 128, 0.34)';
  const cols = Math.ceil((w + h) / step);
  const rows = Math.ceil((w + h) / step);
  for (let i = -cols; i <= cols; i++) {
    for (let j = -rows; j <= rows; j++) {
      ctx.fillText(t, i * step, j * step);
    }
  }
  ctx.restore();
}

// ------------------------------------------------------------- 合成与导出

/**
 * 在画布上完成「人像 + 底色」合成并叠加水印，返回画布（供多次按不同质量导出）
 * @param {object} src 越界补底信息 { srcW, srcH, box }；box 只有顶部可能越出
 *        原图，越界区的 alpha 不信任 MODNet（对补底区域判断不稳），一律置 0
 *        （纯背景），合成时即为目标底色。
 */
function buildComposite(croppedPath, mask, maskWidth, maskHeight, outW, outH, bgHex, watermarkText, src) {
  return new Promise((resolve, reject) => {
    const canvas = wx.createOffscreenCanvas({ type: '2d', width: outW, height: outH });
    const ctx = canvas.getContext('2d');
    const img = canvas.createImage();
    img.onload = () => {
      // 裁剪图与输出规格比例一致，直接铺满即可（无变形）
      ctx.drawImage(img, 0, 0, outW, outH);
      const person = ctx.getImageData(0, 0, outW, outH);
      const alpha = matting.resizeMask(mask, maskWidth, maskHeight, outW, outH);
      if (src && src.box) {
        extendAlphaOutOfSource(alpha, outW, outH, src);
      }
      cleanGhostAlpha(alpha, outW, outH);
      const bg = matting.hexToRgb(bgHex);
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
      if (watermarkText) drawWatermark(ctx, outW, outH, watermarkText);
      resolve(canvas);
    };
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = croppedPath;
  });
}

/**
 * 下半区幽灵残影清理（原位修改）
 * MODNet 对被画面边缘截断的手臂、以及与人体相邻的墙面，常输出 0.2~0.5
 * 的半透明 alpha，在白/浅色底上呈现为肩部两侧的模糊竖条（实机反馈）。
 * 证件照下半区只有肩部/衣服（硬边缘，alpha 过渡本来就陡），低于阈值的
 * 残影直接归 0；上半区发丝的软 alpha 是抠图质量的关键，绝不触碰。
 */
function cleanGhostAlpha(alpha, outW, outH) {
  const GHOST_THRESHOLD = 0.45;
  const startRow = Math.floor(outH * 0.58); // 下半区：肩线以下（发丝集中在上 50%）
  for (let y = startRow; y < outH; y++) {
    const row = y * outW;
    for (let x = 0; x < outW; x++) {
      if (alpha[row + x] < GHOST_THRESHOLD) {
        alpha[row + x] = 0;
      }
    }
  }
}

/**
 * 修正「裁剪框越出原图」区域的 alpha（原位修改）
 * 规则：越界区一律 alpha=0（纯背景色）。calcCropBox 已保证只有顶部可能
 * 越界（头顶之上必是背景），左右/底部越界不会发生，此处仍按通用逻辑
 * 兜底清零。注意不要用「复制交界行/列」的方案——源图边缘的 MODNet
 * alpha 本身不可靠，会把墙面等背景误判为人身，产出悬空色块。
 */
function extendAlphaOutOfSource(alpha, outW, outH, src) {
  const { box, srcW, srcH } = src;
  const pxX = box.width / outW;
  const pxY = box.height / outH;
  const xIn0 = Math.min(outW - 1, Math.max(0, Math.ceil((0 - box.left) / pxX)));
  const xIn1 = Math.min(outW - 1, Math.max(0, Math.floor((srcW - box.left) / pxX) - 1));
  const yIn0 = Math.min(outH - 1, Math.max(0, Math.ceil((0 - box.top) / pxY)));
  const yIn1 = Math.min(outH - 1, Math.max(0, Math.floor((srcH - box.top) / pxY) - 1));
  for (let y = 0; y < outH; y++) {
    const row = y * outW;
    if (y < yIn0 || y > yIn1) {
      for (let x = 0; x < outW; x++) alpha[row + x] = 0;
      continue;
    }
    for (let x = 0; x < xIn0; x++) alpha[row + x] = 0;
    for (let x = xIn1 + 1; x < outW; x++) alpha[row + x] = 0;
  }
}

/** 按指定质量把画布导出为临时 jpg */
function exportCanvas(canvas, w, h, quality) {
  return new Promise((resolve, reject) => {
    wx.canvasToTempFilePath({
      canvas,
      x: 0,
      y: 0,
      width: w,
      height: h,
      destWidth: w,
      destHeight: h,
      fileType: 'jpg',
      quality,
      success: (r) => resolve(r.tempFilePath),
      fail: (e) => reject(new Error('导出失败：' + JSON.stringify(e))),
    });
  });
}

/**
 * 按目标体积导出：在 [Q_MIN, Q_MAX] 内二分质量，找满足 maxKB 的最大质量值。
 * 有 minKB 时若结果过小则适当提高质量（体积下限一般不会被卡，保底处理）。
 */
function exportUnderSize(canvas, w, h, spec) {
  const maxKB = spec && spec.sizeRule !== 'none' ? spec.maxSizeKB : null;
  const minKB = spec && spec.sizeRule === 'range' ? spec.minSizeKB : null;

  return new Promise((resolve) => {
    let lo = Q_MIN;
    let hi = Q_MAX;
    let best = { path: null, quality: hi, kb: Infinity };

    const attempt = (q) =>
      exportCanvas(canvas, w, h, q).then((p) => {
        const kb = matting.fileSizeKB(p);
        return { path: p, quality: q, kb };
      });

    const run = (i) => {
      const q = i === 0 ? hi : (lo + hi) / 2;
      return attempt(q).then((r) => {
        const fits = !maxKB || r.kb <= maxKB;
        if (fits && (best.path === null || r.quality >= best.quality)) best = r;
        if (i >= Q_ITER) {
          // 迭代用尽：有结果就用，没有就用最低质量的
          return best.path ? best : attempt(Q_MIN);
        }
        if (fits) {
          best = r;
          lo = q; // 还能更清晰，往高质量探
        } else {
          hi = q; // 超了，往低质量压
        }
        return run(i + 1);
      });
    };

    run(0).then((r) => {
      // 保底：低于 minKB 时提示（不强行放大体积，避免失真）
      resolve({ path: r.path, quality: r.quality, kb: r.kb, underMin: !!(minKB && r.kb < minKB) });
    });
  });
}

// ------------------------------------------------------------------- 主链路

/**
 * 制作一张证件照
 * @param {object} opts
 *   imgPath    原图路径（wx.chooseMedia original）
 *   exam       specs.js 的考试对象
 *   bgHex      底色
 *   session    已加载的推理会话（matting.createSession 产物）
 *   shortEdge  推理短边，默认 384
 *   watermark  水印文字；传空串则不加水印（付费高清版）
 *   onStep     (step, info) 进度回调：face / crop / matting / compose / size
 * @returns {Promise<object>} 结果（path/kb/quality/box/metrics/faceInfo）
 */
function makeIdPhoto(opts) {
  const o = opts || {};
  const exam = o.exam;
  const spec = exam.image;
  const outW = spec.recommendPx[0];
  const outH = spec.recommendPx[1];
  const ratio = outW / outH;
  const metrics = [];
  const t0 = Date.now();
  const step = o.onStep || function () {};

  return detectFaceOriginal(o.imgPath)
    .then((fd) => {
      metrics.push({ k: '人脸检测', v: fd.costMs ? `${fd.costMs} ms` : '跳过' });
      const faceRatio = (exam.portrait && exam.portrait.cropFaceRatio) || 0.45;
      const box = fd.face
        ? face.calcCropBox(fd.face, fd.origW, fd.origH, ratio, faceRatio)
        : fallbackCropBox(fd.origW, fd.origH, ratio, faceRatio);
      step('crop', { faceFound: !!fd.face });
      return cropAndScale(o.imgPath, box, outW, outH).then((croppedPath) => ({
        fd,
        box,
        croppedPath,
      }));
    })
    .then(({ fd, box, croppedPath }) => {
      step('matting');
      const t1 = Date.now();
      return matting
        .imageToTensor(croppedPath, o.shortEdge || 384)
        .then((tensor) => matting.runMatting(o.session, tensor))
        .then((r) => {
          metrics.push({ k: '端上推理', v: `${r.costMs} ms（蒙版 ${r.maskWidth}×${r.maskHeight}）` });
          return {
            fd,
            box,
            croppedPath,
            mask: r.mask,
            maskWidth: r.maskWidth,
            maskHeight: r.maskHeight,
            mattingMs: Date.now() - t1,
          };
        });
    })
    .then((ctx0) => {
      step('compose');
      const t2 = Date.now();
      return buildComposite(
        ctx0.croppedPath,
        ctx0.mask,
        ctx0.maskWidth,
        ctx0.maskHeight,
        outW,
        outH,
        o.bgHex || '#FFFFFF',
        o.watermark === undefined ? '登科喵证件照 · 预览' : o.watermark,
        { srcW: ctx0.fd.origW, srcH: ctx0.fd.origH, box: ctx0.box }
      ).then((canvas) => {
        metrics.push({ k: '换底合成', v: `${Date.now() - t2} ms` });
        return Object.assign(ctx0, { canvas });
      });
    })
    .then((ctx0) => {
      step('size');
      return exportUnderSize(ctx0.canvas, outW, outH, spec).then((out) => {
        metrics.push({ k: '导出', v: `${out.kb} KB（质量 ${(out.quality * 100).toFixed(0)}%）` });
        metrics.push({ k: '总计', v: `${Date.now() - t0} ms` });
        return {
          path: out.path,
          kb: out.kb,
          quality: out.quality,
          underMin: out.underMin,
          px: [outW, outH],
          bgHex: o.bgHex,
          faceFound: !!ctx0.fd.face,
          faceCostMs: ctx0.fd.costMs,
          keypoints: ctx0.fd.keypoints,
          box: ctx0.box,
          metrics,
          totalMs: Date.now() - t0,
        };
      });
    });
}

module.exports = {
  makeIdPhoto,
  detectFaceOriginal,
  cropAndScale,
  drawWatermark,
  exportCanvas,
  exportUnderSize,
};
