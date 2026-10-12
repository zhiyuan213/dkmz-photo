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
 * 抠图通道二选一（运行时自动降级）：
 *  - 端上 MODNet（默认）：离线、免上传
 *  - 云端精修（opts.cloudSegment）：调用方传入 base64→Promise 函数，走腾讯云 BDA
 *    人像分割，发丝/复杂背景边缘更准；失败或未开通时自动回退端上，功能不中断
 *
 * 隐私：端上通道原图全程不出手机；云端精修开启时裁剪图会上传至腾讯云做分割。
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
function cropAndScale(imgPath, box, outW, outH, quality) {
  const q = quality === undefined ? CROP_QUALITY : quality;
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
        quality: q,
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

      // 边缘去污染（换深色底必做）：半透明边缘像素混着原背景色（常见白墙），
      // 铺红/蓝等深饱和底色时显形为灰霜。先按 alpha 反推纯人像色，
      // 再把过淡的 alpha 光环直接收掉。
      decontaminateEdge(alpha, person.data, outW, outH);

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
 * 边缘去污染 + 轻度 alpha 内收（原位修改）
 *
 * 原理：抠图源是带背景的 JPEG，边缘半透明像素的观测色
 *   C = F·a + B·(1-a)   （F=纯人像色，B=原背景色，a=alpha）
 * 直接拿 C 去和目标底色混合，等于把原背景（白墙）又掺了回去——
 * 白底上无感，红/蓝底上头发边缘就发灰。
 *
 * 处理：
 *  1. 用 a<0.03 的像素均值估计原背景色 B（比硬编码白色更稳，兼容非白背景）；
 *  2. 对 0.10 ≤ a < 0.85 的边缘带反推 F = (C - B·(1-a)) / a 并 clamp；
 *     合成时 F·a + 目标底色·(1-a)，背景影响即被替换为目标底色；
 *  3. a < 0.10 的光环直接置 0（向内收 1px 量级），避免低 alpha 区
 *     反推放大噪声（a=0.1 时误差放大 10 倍）。
 * 白底输出几乎不变（F·a + 白·(1-a) ≈ 原观测值），深色底边缘明显变净。
 */
function decontaminateEdge(alpha, d, outW, outH) {
  const n = outW * outH;
  const LOW = 0.1;
  const HIGH = 0.85;

  // 1) 估计原背景色（近透明像素均值；样本太少则退回纯白）
  let br = 0;
  let bgG = 0;
  let bb = 0;
  let cnt = 0;
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    if (alpha[i] < 0.03) {
      br += d[p];
      bgG += d[p + 1];
      bb += d[p + 2];
      cnt++;
    }
  }
  const B0 = cnt > n * 0.005 ? br / cnt : 255;
  const B1 = cnt > n * 0.005 ? bgG / cnt : 255;
  const B2 = cnt > n * 0.005 ? bb / cnt : 255;

  // 2) 反推 + 收边
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    const a = alpha[i];
    if (a <= 0) continue;
    if (a < LOW) {
      alpha[i] = 0;
      continue;
    }
    if (a >= HIGH) continue; // 实心区不受背景影响，不动
    const w = 1 - a;
    let fr = (d[p] - B0 * w) / a;
    let fg = (d[p + 1] - B1 * w) / a;
    let fb = (d[p + 2] - B2 * w) / a;
    d[p] = fr < 0 ? 0 : fr > 255 ? 255 : fr;
    d[p + 1] = fg < 0 ? 0 : fg > 255 ? 255 : fg;
    d[p + 2] = fb < 0 ? 0 : fb > 255 ? 255 : fb;
  }
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

/** 读临时文件为 base64（云端精修上传用） */
function fileToBase64(path) {
  return new Promise((resolve, reject) => {
    wx.getFileSystemManager().readFile({
      filePath: path,
      encoding: 'base64',
      success: (r) => resolve(r.data),
      fail: (e) => reject(new Error('读取图片失败：' + JSON.stringify(e))),
    });
  });
}

/** 把 base64 PNG 写成临时文件（offscreen canvas 的 Image 对 temp 路径支持最稳） */
function base64ToTempPng(b64) {
  return new Promise((resolve, reject) => {
    const p = `${wx.env.USER_DATA_PATH}/cloud_segment_${Date.now()}.png`;
    wx.getFileSystemManager().writeFile({
      filePath: p,
      data: b64,
      encoding: 'base64',
      success: () => resolve(p),
      fail: (e) => reject(new Error('写入分割结果失败：' + JSON.stringify(e))),
    });
  });
}

/**
 * 从云端返回的透明底 PNG 提取 alpha 蒙版。
 * 输入即裁剪图（outW×outH），返回 PNG 尺寸与之一致，后续 resizeMask 零缩放直通。
 */
function maskFromPng(pngPath) {
  return new Promise((resolve, reject) => {
    const probe = wx.createOffscreenCanvas({ type: '2d', width: 8, height: 8 });
    const img = probe.createImage();
    img.onload = () => {
      const w = img.width;
      const h = img.height;
      const canvas = wx.createOffscreenCanvas({ type: '2d', width: w, height: h });
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, w, h);
      const d = ctx.getImageData(0, 0, w, h).data;
      const n = w * h;
      const mask = new Float32Array(n);
      for (let i = 0; i < n; i++) mask[i] = d[i * 4 + 3] / 255;
      resolve({ mask, maskWidth: w, maskHeight: h });
    };
    img.onerror = () => reject(new Error('分割结果解码失败'));
    img.src = pngPath;
  });
}

/**
 * 云端精修抠图：单独产一张「上传小图」（长边 512、质量 0.8），
 * 保证 base64 < 100KB —— callContainer 请求包硬限制 100KB（-606001），
 * 微信二次压缩过的相册图噪点多，480×640@0.95 的原图裁剪会超限。
 * 蒙版后续会 resizeMask 缩放回输出尺寸，上传图小一点不影响分割精度。
 * cloudSegment(base64) 需 resolve { ok:true, image:<png base64> }，reject / ok:false 一律视为失败。
 */
const CLOUD_UPLOAD_LONG_EDGE = 512;
const CLOUD_UPLOAD_QUALITY = 0.8;
const CLOUD_PAYLOAD_LIMIT = 90 * 1024; // base64 字节上限（100KB 留余量）

function cloudMatting(imgPath, box, outW, outH, cloudSegment) {
  const s = CLOUD_UPLOAD_LONG_EDGE / Math.max(outW, outH);
  const w = Math.max(1, Math.round(outW * s));
  const h = Math.max(1, Math.round(outH * s));
  return cropAndScale(imgPath, box, w, h, CLOUD_UPLOAD_QUALITY)
    .then((uploadPath) => fileToBase64(uploadPath))
    .then((b64) => {
      if (b64.length > CLOUD_PAYLOAD_LIMIT) {
        throw new Error(`上传图过大(${Math.round(b64.length / 1024)}KB)`);
      }
      return cloudSegment(b64);
    })
    .then((res) => {
      if (!res || !res.ok || !res.image) {
        throw new Error((res && res.msg) || '云端精修不可用');
      }
      return base64ToTempPng(res.image)
        .then(maskFromPng)
        .then((m) => {
          m.beautyApplied = res.beautyApplied === true;
          return m;
        });
    });
}

/** 端上 MODNet 抠图（原有链路） */
function localMatting(croppedPath, session, shortEdge) {
  return matting
    .imageToTensor(croppedPath, shortEdge)
    .then((tensor) => matting.runMatting(session, tensor))
    .then((r) => ({
      mask: r.mask,
      maskWidth: r.maskWidth,
      maskHeight: r.maskHeight,
    }));
}

/**
 * 制作一张证件照
 * @param {object} opts
 *   imgPath       原图路径（wx.chooseMedia original）
 *   exam          specs.js 的考试对象
 *   bgHex         底色
 *   session       已加载的推理会话（matting.createSession 产物）
 *   shortEdge     推理短边，默认 384
 *   watermark     水印文字；传空串则不加水印（付费高清版）
 *   cloudSegment  可选，云端精修：(裁剪图 base64) => Promise<{ok,image}>；
 *                 失败/未开通自动回退端上 MODNet
 *   onStep        (step, info) 进度回调：face / crop / matting / compose / size
 * @returns {Promise<object>} 结果（path/kb/quality/box/metrics/faceInfo/mattingMode）
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
      const finish = (r, mode) => {
        const ms = Date.now() - t1;
        metrics.push({
          k: mode === 'cloud' ? '云端抠图' : '端上推理',
          v: mode === 'cloud' ? `${ms} ms（腾讯云 BDA）` : `${ms} ms（蒙版 ${r.maskWidth}×${r.maskHeight}）`,
        });
        if (mode === 'cloud') {
          metrics.push({ k: '肤色提亮', v: r.beautyApplied ? '已应用' : '未生效' });
        }
        return {
          fd,
          box,
          croppedPath,
          mask: r.mask,
          maskWidth: r.maskWidth,
          maskHeight: r.maskHeight,
          mattingMs: ms,
          mattingMode: mode,
        };
      };
      const local = () => localMatting(croppedPath, o.session, o.shortEdge || 384).then((r) => finish(r, 'local'));
      if (typeof o.cloudSegment !== 'function') {
        return local();
      }
      // 云端优先，任何失败（未开通/网络/服务错误）静默回退端上，出片不中断
      return cloudMatting(o.imgPath, box, outW, outH, o.cloudSegment)
        .then((r) => finish(r, 'cloud'))
        .catch((e) => {
          // api 层 reject 的是 {code,msg} 对象而非 Error，msg/message 都兜住
          const why = ((e && (e.msg || e.message)) ? String(e.msg || e.message) : '')
            .replace(/\s+/g, ' ').trim().slice(0, 50);
          console.warn('[photo][pipeline] 云端精修失败，回退端上抠图：', e && e.message);
          // 失败原因透出到结果页「处理明细」，便于用户/排查直接看到 BDA 报错
          metrics.push({ k: '云端抠图', v: `失败，已回退端上${why ? `（${why}）` : ''}` });
          return local();
        });
    })
    .then((ctx0) => {
      step('compose');
      const t2 = Date.now();
      // 合成干净的画布（无水印）；水印在导出阶段按版本叠加
      const wm = o.watermark === undefined ? '登科喵证件照 · 预览' : o.watermark;
      return buildComposite(
        ctx0.croppedPath,
        ctx0.mask,
        ctx0.maskWidth,
        ctx0.maskHeight,
        outW,
        outH,
        o.bgHex || '#FFFFFF',
        null,
        {
          srcW: ctx0.fd.origW,
          srcH: ctx0.fd.origH,
          box: ctx0.box,
          face: ctx0.fd.face,
        }
      ).then((canvas) => {
        metrics.push({ k: '换底合成', v: `${Date.now() - t2} ms` });
        return Object.assign(ctx0, { canvas, wm });
      });
    })
    .then((ctx0) => {
      step('size');
      // ① 高清无水印版：按规格体积二分（消耗权益后保存的就是它）
      return exportUnderSize(ctx0.canvas, outW, outH, spec).then((out) => {
        metrics.push({ k: '高清导出', v: `${out.kb} KB（质量 ${(out.quality * 100).toFixed(0)}%，无水印）` });
        // ② 水印预览版：展示用，不做体积二分（一次导出即可）
        const drawPreview = () => {
          if (!ctx0.wm) return Promise.resolve(out.path);
          drawWatermark(ctx0.canvas.getContext('2d'), outW, outH, ctx0.wm);
          return exportCanvas(ctx0.canvas, outW, outH, 0.85);
        };
        return drawPreview().then((previewPath) => {
          metrics.push({ k: '总计', v: `${Date.now() - t0} ms` });
          return {
            path: out.path,          // 高清无水印（消耗权益后保存）
            previewPath,             // 带水印预览（结果页展示 / 降级保存）
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
