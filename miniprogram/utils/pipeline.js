/**
 * 证件照制作流水线（阶段 1 · 免费链路）
 *
 * 选照片 → 人脸检测定位 → **本地**构图裁剪 → （完整原图送检）内容安全检测
 *        → 云端抠图 → 换底合成 → 叠加预览水印 → 高清导出
 *
 * ⚠️ 顺序说明：端上的「本地构图裁剪」发生在内容安全检测**之前**（只为缩小上传体积，
 *    全程不出手机、不产出任何结果）；但送给微信检测的是**未裁剪的完整原图**，
 *    且腾讯云的抠图严格发生在检测通过**之后**。详见 checkImageBase64 的注释。
 *
 * 依赖：
 *  - utils/face.js   VisionKit 静态图人脸检测 + calcCropBox 构图
 *  - utils/matting.js 仅用于端上的图像编解码/蒙版工具（imageSize / resizeMask /
 *    hexToRgb / fileSizeKB）；其 MODNet 推理已不参与出片。
 *
 * 抠图通道：**只有云端精修**（opts.cloudSegment）—— 腾讯云 BDA 人像分割（+ 可选面部皮肤美白）。
 * 端上 MODNet 已不参与出片（降级会让用户拿到不一致的成片，且会绕过内容安全检测），
 * 任何失败都直接抛出、由页面明确提示；推理仅保留在 matting.js 供实验室页自检。
 *
 * 云端返回三种形态（按后端能力/入参自动识别）：
 *  - alpha 灰度图（alphaOnly=true，只替换背景）：云端只回 alpha（10~30KB），
 *    端上用本地裁剪图 + alpha 自己换底 —— 端上像素零损失、不经有损 JPEG，
 *    与「成品图模式」相比少传一份 RGB
 *  - 成品图（res.finished=true，替换背景）：云端换底及边缘净化后回传，
 *    端上保留处理分辨率，只加预览水印并导出高清图。
 *  - 透明底 PNG（老后端）：端上按 alpha 自行换底合成（保留的历史链路）
 *    后两类的端上处理顺序（换深色底时全开，白底自动全部跳过）：
 *      cleanGhostAlpha → computeWallMask(原始数据) → decontaminateEdge → processHair
 *    computeWallMask 必须在 decontaminateEdge 之前算，因为去污染会改写 alpha/RGB、
 *    污染泛洪种子；processHair 用它的结果治深色底上的发丝白霜。
 *
 * 隐私：只有云端一条通道，每次制作会上传两份图 ——
 *      ① **完整原图**（等比缩放后的 JPEG）：仅用于微信内容安全检测（mediaCheckAsync），
 *         检测通过后才继续处理；
 *      ② 证件照裁剪工作图：上传至腾讯云做人像分割后回传。
 */

const matting = require('./matting');
const face = require('./face');
const { cloudProcessingSizes } = require('./processing-size');

/** 人脸检测送检上限（最长边），兼顾速度与精度；坐标按比例映射回原图 */
const FACE_DETECT_CAP = 1280;

/** 裁剪导出的中间图质量 */
const CROP_QUALITY = 0.95;

/** 质量二分范围与迭代上限 */
const Q_MIN = 0.35;
const Q_MAX = 0.95;
const Q_ITER = 7;

/**
 * 制作流程的 5 个阶段 —— **唯一来源**。
 * 序号有三个去处，任何一处调顺序都必须只改这里：
 *   ① 控制台流程日志（`[photo][流程] N/5 …`）；
 *   ② 结果页步骤条（pages/make/make.js 直接引用本常量，不再自己抄一份）；
 *   ③ 与真实执行顺序的对照（端上 ①crop → 服务端 ②seccheck → 云端 ③matting）。
 */
const STEPS = [
  { key: 'crop', seq: 1, label: '人脸检测 · 智能裁剪' },
  { key: 'seccheck', seq: 2, label: '内容安全检测' },
  { key: 'matting', seq: 3, label: '云端抠图 · 换底' },
  { key: 'compose', seq: 4, label: '换底合成' },
  { key: 'size', seq: 5, label: '高清导出' },
];

/** key → 步骤条目，供流程日志取序号与名称 */
const STEP_BY_KEY = STEPS.reduce((m, s) => {
  m[s.key] = s;
  return m;
}, {});

/**
 * 流程日志：进入每个阶段打一行，前面带序号（1/5 … 5/5）。
 * 只打阶段名与耗时，不含任何图像数据；超时/卡住时照序号就能定位在哪一步。
 *
 * 耗时措辞是「间隔」而非「上一步耗时」：日志在**阶段完成时**才打，所以第 3 行的
 * 间隔 = 上传 + 微信送检 + 云端抠图的合计（这段正是 -600001 超时的所在），
 * 别当成抠图自己花了这么多。
 *
 * @param {string} key        阶段 key（STEPS 之一）
 * @param {number} sincePrev  距上一条流程日志的毫秒数
 * @param {number} sinceStart 距开始制作的毫秒数
 */
function logStep(key, sincePrev, sinceStart) {
  const it = STEP_BY_KEY[key];
  if (!it) return;
  console.log('[photo][流程] ' + it.seq + '/' + STEPS.length + ' ' + it.label
    + ' | 间隔 ' + sincePrev + 'ms | 累计 ' + sinceStart + 'ms');
}

/** 像素值夹取到 [0,255]（去色边反推 F=(C-B·w)/(1-w) 时可能越界） */
function clamp(v) {
  return v < 0 ? 0 : v > 255 ? 255 : v;
}

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

function enableHighQualityScale(ctx) {
  try {
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
  } catch (e) {
    // 部分基础库没有 imageSmoothingQuality，保留默认缩放行为即可。
  }
}

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
    enableHighQualityScale(ctx);
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
    enableHighQualityScale(ctx);
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
      // 再把过淡的 alpha 光环直接收掉。残影重估只在目标底比原背景暗时启用
      //（白底→白底跳过：头发/亮肤本就像「白墙混合色」，重估会误伤成银灰）。
      const bg = matting.hexToRgb(bgHex);

      // 头发白霜修复的作用区（墙体泄漏区）必须在去污染**之前**、用原始数据计算：
      // 去污染会改写 alpha 与 RGB，残留的低 alpha 点会污染泛洪种子。
      // 白底场景整个跳过（与上面残影重估同一门控）——一来墙体泄漏区是整图 BFS，
      // 白底没必要跑；二来白底上「灰白」与「半透明深色」观感一致，处理只会误伤。
      const wall = targetIsDark(alpha, person.data, alpha.length, bg)
        ? computeWallMask(alpha, person.data, outW, outH)
        : null;

      decontaminateEdge(alpha, person.data, outW, outH, bg);

      // 头发白霜修复（深色底专用）：见 processHair 注释
      if (wall) processHair(alpha, person.data, outW, outH, wall);

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
 * 成品图铺满画布（云端成品图路径）
 * 像素、换底、边缘净化都已在云端完成，这里只把成品图放到离屏画布上，
 * 供后续「加水印预览」与「高清导出」复用同一块画布。
 * 尺寸不一致时按比例铺满（正常情况两者相同，零缩放）。
 */
function canvasFromImage(imgPath, outW, outH) {
  return new Promise((resolve, reject) => {
    const canvas = wx.createOffscreenCanvas({ type: '2d', width: outW, height: outH });
    const ctx = canvas.getContext('2d');
    enableHighQualityScale(ctx);
    const img = canvas.createImage();
    img.onload = () => {
      ctx.drawImage(img, 0, 0, outW, outH);
      resolve(canvas);
    };
    img.onerror = () => reject(new Error('成品图解码失败'));
    img.src = imgPath;
  });
}

/**
 * 最终缩小后做轻量反锐化遮罩，补回证件照小尺寸导出损失的轮廓细节。
 * 调整量限制在 ±6，避免生成不存在的纹理或放大抠图边缘噪点。
 */
function sharpenCanvas(canvas, width, height) {
  if (width < 3 || height < 3) return;
  const ctx = canvas.getContext('2d');
  let imageData;
  try {
    imageData = ctx.getImageData(0, 0, width, height);
  } catch (e) {
    return;
  }
  const data = imageData.data;
  const source = new Uint8ClampedArray(data);
  const luminance = (i) => source[i] * 0.299 + source[i + 1] * 0.587 + source[i + 2] * 0.114;
  const clamp = (v) => Math.max(0, Math.min(255, v));
  for (let y = 1; y < height - 1; y += 1) {
    for (let x = 1; x < width - 1; x += 1) {
      const i = (y * width + x) * 4;
      const blurred = (
        luminance(i - 4) + luminance(i + 4)
        + luminance(i - width * 4) + luminance(i + width * 4)
      ) / 4;
      const delta = luminance(i) - blurred;
      if (Math.abs(delta) < 2) continue;
      const adjustment = Math.max(-6, Math.min(6, delta * 0.28));
      data[i] = clamp(source[i] + adjustment);
      data[i + 1] = clamp(source[i + 1] + adjustment);
      data[i + 2] = clamp(source[i + 2] + adjustment);
    }
  }
  ctx.putImageData(imageData, 0, 0);
}

/**
 * 边缘去污染 + 白墙残影清理（原位修改）
 *
 * 两类背景污染，同一个反推模型 C = F·a + B·(1-a)（F=纯人像色，B=原背景色）：
 *
 * 【① 半透明边缘】alpha 0.1~0.85 的边缘带像素混着原背景色，
 *   直接铺红/蓝底显形为灰霜 → 用 mask 给出的 1-a 反推纯人像色。
 *
 * 【② 白墙残影（实心）】蓬松发丝 + 浅色背景处，分割把 alpha 判成实心，
 *   但颜色是「发丝+白墙」的低饱和灰白混合色（实测头顶带 17% 像素，
 *   均值 RGB≈187,178,175）——白底输出无感，红/蓝底上是头顶白霜。
 *   mask 证据失效，改用颜色证据重估背景占比 w：低饱和、越亮越像背景，
 *   w = (lum − 70) / (B亮度 − 70)。仅作用于上半区、且距真背景
 *   ≤ GHOST_DIST px 的像素（chamfer 距离场约束，保护镜片反光等
 *   远离背景的亮色区域）。
 *
 * 处理后写回纯前景色 F = (C − B·w)/(1−w)，alpha 更新为 1−w，
 * 后续与目标底色的混合即完成「背景贡献 → 新底色」的置换。
 * 白底输出几乎不变（F·a + 白·(1-a) ≈ 原观测值），深色底边缘明显变净。
 */
function decontaminateEdge(alpha, d, outW, outH, targetBg) {
  const n = outW * outH;
  const GHOST_DIST = 14;
  const SAT_MAX = 42;
  const ghostRow = Math.floor(outH * 0.58);

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
  const LB = (B0 + B1 + B2) / 3;
  const LUM_MIN = Math.max(150, LB - 70);

  // 残影重估门控：白底→白底必须跳过（头发/亮肤像素本就低饱和偏亮，重估会把
  // alpha 砍掉、前景反推钳制成黑 ⇒ 白底上整片银灰，10-12 真机事故）；
  // 且白底目标下「灰白实心」与「半透明深色」观感几乎一致，跳过零损失。
  // 红/蓝底（比白墙暗 100+ 亮度）照常启用，治头顶白霜。
  const targetLuma = targetBg ? (targetBg[0] + targetBg[1] + targetBg[2]) / 3 : 255;
  const ghostOn = LB - targetLuma >= 40;

  // 2) chamfer 距离场：0 = 确定背景（alpha<0.08），向外近似欧氏距离
  const dist = new Float32Array(n).fill(1e6);
  for (let i = 0; i < n; i++) if (alpha[i] < 0.08) dist[i] = 0;
  for (let y = 0; y < outH; y++) {
    for (let x = 0; x < outW; x++) {
      const i = y * outW + x;
      let v = dist[i];
      if (x > 0 && dist[i - 1] + 1 < v) v = dist[i - 1] + 1;
      if (y > 0) {
        if (dist[i - outW] + 1 < v) v = dist[i - outW] + 1;
        if (x > 0 && dist[i - outW - 1] + 1.41 < v) v = dist[i - outW - 1] + 1.41;
        if (x < outW - 1 && dist[i - outW + 1] + 1.41 < v) v = dist[i - outW + 1] + 1.41;
      }
      dist[i] = v;
    }
  }
  for (let y = outH - 1; y >= 0; y--) {
    for (let x = outW - 1; x >= 0; x--) {
      const i = y * outW + x;
      let v = dist[i];
      if (x < outW - 1 && dist[i + 1] + 1 < v) v = dist[i + 1] + 1;
      if (y < outH - 1) {
        if (dist[i + outW] + 1 < v) v = dist[i + outW] + 1;
        if (x < outW - 1 && dist[i + outW + 1] + 1.41 < v) v = dist[i + outW + 1] + 1.41;
        if (x > 0 && dist[i + outW - 1] + 1.41 < v) v = dist[i + outW - 1] + 1.41;
      }
      dist[i] = v;
    }
  }

  // 3) 反推 + 残影重估 + 收边
  for (let y = 0; y < outH; y++) {
    const inGhost = y < ghostRow;
    for (let x = 0; x < outW; x++) {
      const i = y * outW + x;
      const a = alpha[i];
      if (a <= 0) continue;
      const p = i * 4;
      let w = 1 - a; // mask 证据：背景占比

      // 颜色证据：上半区、近背景、低饱和、亮的像素按亮度重估
      if (ghostOn && inGhost && dist[i] <= GHOST_DIST) {
        const r = d[p];
        const g = d[p + 1];
        const b = d[p + 2];
        if (Math.max(r, g, b) - Math.min(r, g, b) <= SAT_MAX) {
          const lum = (r + g + b) / 3;
          if (lum >= LUM_MIN) {
            w = Math.max(w, Math.min(1, (lum - 70) / (LB - 70)));
          }
        }
      }

      if (w >= 0.9) {          // 背景占比过高（含原 a<0.1 清零逻辑）
        alpha[i] = 0;
        continue;
      }
      const inv = 1 / (1 - w);
      d[p] = clamp((d[p] - B0 * w) * inv);
      d[p + 1] = clamp((d[p + 1] - B1 * w) * inv);
      d[p + 2] = clamp((d[p + 2] - B2 * w) * inv);
      alpha[i] = 1 - w;
    }
  }
}

/**
 * 目标底色是否「明显比原背景暗」——即需要边缘净化/头发修复的深色底。
 *
 * 判据与 decontaminateEdge 内部的残影重估门控一致：原背景亮度 − 目标底亮度 ≥ 40。
 * 之所以单独算一次（而不是复用 decontaminateEdge 的结果），是因为头发修复必须
 * 在去污染**之前**决定要不要算墙体泄漏区（那是整图 BFS，白底场景没必要跑）。
 *
 * @param {Float32Array} alpha 抠图 alpha（此时仍是原始值）
 * @param {Uint8ClampedArray} d RGBA 像素
 * @param {number} n 像素数
 * @param {number[]} targetBg 目标底色 [r,g,b]
 */
function targetIsDark(alpha, d, n, targetBg) {
  let sr = 0;
  let sg = 0;
  let sb = 0;
  let cnt = 0;
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    if (alpha[i] < 0.03) {
      sr += d[p];
      sg += d[p + 1];
      sb += d[p + 2];
      cnt++;
    }
  }
  // 样本太少说明 alpha 全糊了，按「不改」处理（宁可不修，不要误修）
  if (cnt <= n * 0.005) return false;
  const origLuma = (sr + sg + sb) / cnt / 3;
  const targetLuma = (targetBg[0] + targetBg[1] + targetBg[2]) / 3;
  return origLuma - targetLuma >= 40;
}

/**
 * 墙体泄漏区（供头发修复做作用区；移植自 tools/local_render.py v3 定稿）
 * 从真背景种子（原始 alpha<0.05）沿「中性灰白+亮」像素 BFS 生长，限上半区。
 * r-b ≤ 22 是皮肤防线：黄种人皮肤 r 明显大于 b，白墙是中性色——泛洪进不了脸。
 * 调用时机：必须在 decontaminateEdge 之前、用原始数据计算。
 * @returns {Uint8Array} 1=墙体泄漏区
 */
function computeWallMask(alpha, d, outW, outH) {
  const n = outW * outH;
  const top = Math.floor(outH * 0.62);
  const SAT_MAX = 40;
  const LUM_MIN = 130;
  const RB_MAX = 22;

  const visited = new Uint8Array(n);
  const queue = new Int32Array(n);
  let qh = 0;
  let qt = 0;
  for (let i = 0; i < n; i++) {
    if (alpha[i] < 0.05) {
      visited[i] = 1;
      queue[qt++] = i;
    }
  }
  while (qh < qt) {
    const i = queue[qh++];
    const x = i % outW;
    const y = (i - x) / outW;
    let j;
    if (x + 1 < outW) {
      j = i + 1;
      if (!visited[j] && isWallish(j)) { visited[j] = 1; queue[qt++] = j; }
    }
    if (x > 0) {
      j = i - 1;
      if (!visited[j] && isWallish(j)) { visited[j] = 1; queue[qt++] = j; }
    }
    if (y + 1 < top) {
      j = i + outW;
      if (!visited[j] && isWallish(j)) { visited[j] = 1; queue[qt++] = j; }
    }
    if (y > 0) {
      j = i - outW;
      if (!visited[j] && isWallish(j)) { visited[j] = 1; queue[qt++] = j; }
    }
  }
  // 返回 visited ∩ wallish（对齐 Python 版 visited & wallish）
  const wall = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    if (visited[i] && isWallish(i)) wall[i] = 1;
  }
  return wall;

  function isWallish(i) {
    const p = i * 4;
    const r = d[p];
    const g = d[p + 1];
    const b = d[p + 2];
    const sat = Math.max(r, g, b) - Math.min(r, g, b);
    const lum = (r + g + b) / 3;
    return sat <= SAT_MAX && lum >= LUM_MIN && r - b <= RB_MAX;
  }
}

/**
 * 头发白霜修复（连续键控版，原位修改；移植自 tools/local_render.py v3 定稿）
 * BDA/MODNet 对细发丝给不出实心 alpha（0.2~0.7 软过渡），发丝像素是
 * 「发色 + 白墙」的混合色，深底上反推残留白墙亮色 → 头顶灰白霜。
 * 用 computeWallMask 的墙体连通区限定作用范围：
 *  1) 泛洪区内接近纯墙的像素（lum≥235）直接 alpha=0（发冠内部灰色小孔清干净）
 *  2) 作用区 = wall 膨胀 5px ∩ 上半区 ∩ 中性色（rb≤25, sat≤55）
 *     —— 脸/镜框够不到泛洪区，天然免疫
 *  3) 区内连续键控：t=墙占比（lum 200→0, 245→1），alpha×(1−t)；
 *     亮度向发丝暗部 HD 压缩（keep=0.5×alpha，越透明压得越狠）
 * @returns {number} HD 发丝暗部中位亮度（调试用）
 */
function processHair(alpha, d, outW, outH, wall) {
  const n = outW * outH;
  const top = Math.floor(outH * 0.62);

  // 预计算亮度/饱和度/r-b（rgb 全程不变，共用一份）
  const lum = new Float32Array(n);
  const sat = new Float32Array(n);
  const rb = new Float32Array(n);
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    const r = d[p];
    const g = d[p + 1];
    const b = d[p + 2];
    lum[i] = (r + g + b) / 3;
    sat[i] = Math.max(r, g, b) - Math.min(r, g, b);
    rb[i] = r - b;
  }

  // 1) 泛洪区内接近纯墙 → alpha=0
  for (let i = 0; i < n; i++) {
    if (wall[i] && lum[i] >= 235) alpha[i] = 0;
  }

  // 2) 作用区 = wall 的 5x5 膨胀 ∩ 上半区 ∩ 中性色 ∩ alpha>0
  const zone = new Uint8Array(n);
  for (let y = 0; y < top; y++) {
    for (let x = 0; x < outW; x++) {
      const i = y * outW + x;
      if (alpha[i] <= 0) continue;
      let near = 0;
      for (let dy = -2; dy <= 2 && !near; dy++) {
        const yy = y + dy;
        if (yy < 0 || yy >= outH) continue;
        const row = yy * outW;
        for (let dx = -2; dx <= 2; dx++) {
          const xx = x + dx;
          if (xx >= 0 && xx < outW && wall[row + xx]) {
            near = 1;
            break;
          }
        }
      }
      if (near && rb[i] <= 25 && sat[i] <= 55) zone[i] = 1;
    }
  }

  // 发丝暗部中位数 HD（上半区、非墙、较实心且较暗的像素）
  const vals = [];
  for (let y = 0; y < top; y++) {
    for (let x = 0; x < outW; x++) {
      const i = y * outW + x;
      if (!wall[i] && alpha[i] > 0.5 && lum[i] < 100) vals.push(lum[i]);
    }
  }
  vals.sort((a, b) => a - b);
  const HD = vals.length ? vals[Math.floor(vals.length / 2)] : 60;

  // 3) 连续键控 + 高光压暗
  for (let i = 0; i < n; i++) {
    if (!zone[i]) continue;
    const t = Math.min(1, Math.max(0, (lum[i] - 200) / 45));
    alpha[i] *= 1 - t;
    const keep = 0.5 * alpha[i];
    const f = (HD + (lum[i] - HD) * keep) / Math.max(lum[i], 1);
    if (lum[i] >= HD + 20) {
      const p = i * 4;
      d[p] = clamp(d[p] * f);
      d[p + 1] = clamp(d[p + 1] * f);
      d[p + 2] = clamp(d[p + 2] * f);
    }
  }
  return HD;
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

/** 把 base64 图片写成临时文件（offscreen canvas 的 Image 对 temp 路径支持最稳） */
function base64ToTempFile(b64, ext) {
  return new Promise((resolve, reject) => {
    const p = `${wx.env.USER_DATA_PATH}/cloud_segment_${Date.now()}.${ext || 'png'}`;
    wx.getFileSystemManager().writeFile({
      filePath: p,
      data: b64,
      encoding: 'base64',
      success: () => resolve(p),
      fail: (e) => reject(new Error('写入分割结果失败：' + JSON.stringify(e))),
    });
  });
}

/** 回收落盘的临时中间产物（USER_DATA_PATH 不会自动清理），失败忽略 */
function removeTempFile(p) {
  if (!p) return;
  try {
    wx.getFileSystemManager().unlink({ filePath: p, fail: () => {} });
  } catch (e) {
    /* 忽略 */
  }
}

/**
 * 从云端返回的透明底 PNG 提取 alpha 蒙版（老后端兼容链路）。
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
 * 从云端返回的 alpha 灰度 PNG 提取蒙版（「只替换背景」链路）。
 *
 * 后端 alphaOnly 模式回的是 8 位灰度 PNG（无 alpha 通道），编码约定
 * 「灰度值 = alpha 值」⇒ 取 R 通道即可（灰度图 R=G=B）。与 maskFromPng 的
 * 区别就在这一个通道：灰度图 alpha 通道恒为 255，读 d[i*4+3] 会得到全 1 的废蒙版。
 *
 * 为什么这条链路更划算：不美颜时端上本地像素就是最终像素，服务端只提供
 * alpha 这一份新信息即可 ⇒ 响应从 200~400KB 降到 10~30KB，且不经过有损 JPEG。
 */
function maskFromGrayPng(pngPath) {
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
      for (let i = 0; i < n; i++) mask[i] = d[i * 4] / 255;
      resolve({ mask, maskWidth: w, maskHeight: h });
    };
    img.onerror = () => reject(new Error('alpha 灰度图解码失败'));
    img.src = pngPath;
  });
}

/** 工作图优先长边 960；先调整 JPEG 质量，再逐档降低尺寸。 */
// 优先保留纹理；预算不足时降低工作图尺寸，避免大图被压成明显的色块。
const CLOUD_UPLOAD_QUALITY_LADDER = [0.95, 0.9, 0.85, 0.8];

// ⚠️ CallContainer 对**文本**请求体有 100KiB 硬上限。官方《微信云托管开发常识》原文：
//    「CallContainer 请求大小限制 100K，请求中不建议包含图片，可通过对象存储处理。」
//    《调用云托管服务》亦写明「请求大小限制 100KiB（文本 100KiB，二进制 20MiB）」。
// 现在完整原图送检和裁剪工作图分两次请求；两次分别受 100KiB 限制。一旦超出，链路层就拒绝（前端只看到
// `cloud.callContainer:fail -600001`，**后端根本收不到请求**，日志里什么也没有）——
// 2026-10-15 就是因为送检原图未计体积预算而踩这个坑。
const CLOUD_BODY_BUDGET = 95 * 1024;    // 整个 body（含 JSON 外壳）的目标上限
const CLOUD_BODY_RESERVE = 1024;        // 预留给 JSON 外壳 / loginCode / 凭证等字段
/** 裁剪工作图独占第二次请求的预算，送腾讯云 BDA 抠图。 */
const CLOUD_PAYLOAD_LIMIT = 92 * 1024;

// -------------------------------------- 内容安全检测送检图（完整原图）

/**
 * 送检图的尺寸/质量阶梯与体积上限。
 *
 * 送检图必须是**完整原图**（整幅等比缩放），绝不能是证件照裁剪图：
 * 证件照构图只保留头部与肩部，裁剪会把落在框外的违规内容（裸露、纹身、
 * 违禁物等）直接裁掉，微信只能判 pass —— 实测一张裸照裁剪后过检。
 *
 * 尺寸/质量可以降（控制体积），但**覆盖范围不能降**：宁可用小一号的整幅图，
 * 也绝不能用大一号的裁剪图。
 */
const CHECK_SIZE_LADDER = [1024, 768, 640, 512];
const CHECK_QUALITY_LADDER = [0.78, 0.62, 0.45, 0.35];
/**
 * 送检图独占第一次请求的预算，保留足够像素用于内容安全识别。
 */
const CHECK_PAYLOAD_LIMIT = 88 * 1024;

/** 整幅原图等比缩放到 w×h（不裁剪、不留白）后返回画布 */
function wholeImageCanvas(imgPath, w, h) {
  return new Promise((resolve, reject) => {
    const canvas = wx.createOffscreenCanvas({ type: '2d', width: w, height: h });
    const ctx = canvas.getContext('2d');
    enableHighQualityScale(ctx);
    const img = canvas.createImage();
    img.onload = () => {
      ctx.drawImage(img, 0, 0, w, h);
      resolve(canvas);
    };
    img.onerror = () => reject(new Error('检测送检图解码失败'));
    img.src = imgPath;
  });
}

/**
 * 生成内容安全检测送检图（完整原图的 JPEG base64）。
 * 尺寸/质量逐档下探直到落入体积上限；换尺寸才重建画布，同一画布上换质量只重编码。
 * 失败直接上抛：送检是这个链路的必要环节，生成不了送检图就不能继续
 *（与后端 fail-closed 同一语义），绝不静默退回「送裁剪图」。
 *
 * @param {number} [limit] 本次送检请求的 base64 体积上限（字节）。
 */
function checkImageBase64(imgPath, limit) {
  const cap = limit > 0 ? limit : CHECK_PAYLOAD_LIMIT;
  return matting.imageSize(imgPath).then(({ width, height }) => {
    const build = (sizeIndex) => {
      const scale = Math.min(1, CHECK_SIZE_LADDER[sizeIndex] / Math.max(width, height));
      const w = Math.max(16, Math.round(width * scale));
      const h = Math.max(16, Math.round(height * scale));
      return wholeImageCanvas(imgPath, w, h).then((canvas) => ({ canvas, w, h }));
    };
    const attempt = (sizeIndex, qualityIndex) =>
      build(sizeIndex)
        .then(({ canvas, w, h }) => exportCanvas(canvas, w, h, CHECK_QUALITY_LADDER[qualityIndex]))
        .then((p) =>
          fileToBase64(p).then(
            (b64) => {
              removeTempFile(p);
              return b64;
            },
            (error) => {
              removeTempFile(p);
              throw error;
            }
          )
        )
        .then((b64) => {
          if (b64.length <= cap) return b64;
          if (qualityIndex + 1 < CHECK_QUALITY_LADDER.length) return attempt(sizeIndex, qualityIndex + 1);
          if (sizeIndex + 1 < CHECK_SIZE_LADDER.length) return attempt(sizeIndex + 1, 0);
          // 最小档（512 长边 / 质量 0.35）仍超限：返回它并交由上层断言拦下——
          // 绝不能"保体积"到把整张图丢掉，也不能"保体积"到让请求超限失败
          //（超限 = callContainer -600001，后端收不到任何请求）。
          return b64;
        });
    return attempt(0, 0);
  });
}

/** 裁剪框越出原图的顶部行数（calcCropBox 只允许顶部越界），用于告知云端强制透明 */
function outOfSourceTopRows(box, outH) {
  const pxY = box.height / outH;
  return Math.min(outH, Math.max(0, Math.ceil((0 - box.top) / pxY)));
}

/**
 * 云端精修主流程。
 *
 * 先用 cloudPrecheck 单独提交**完整原图**，取得一次性过检凭证；
 * 再用 cloudSegment 提交裁剪工作图。两次请求都独立受 100KiB 文本体积限制。
 *
 * cloudSegment(base64, extra) 需 resolve：
 *   - 只替换背景（alphaOnly）：{ ok:true, alpha, width, height }
 *   - 成品图模式：{ ok:true, image, finished:true, beautyApplied? }
 *   - 兼容模式：{ ok:true, image(透明底 PNG), origBg?, beautyApplied? }
 * reject / ok:false 一律视为失败 —— 上层直接中断并提示（内容安全检测不通过时
 * 后端会回 code=RISKY_CONTENT，绝不允许回退到端上抠图绕开检测）。
 *
 * @param {object} req { imgPath, box, outW, outH, bgHex, outTop, alphaOnly }
 * @returns {Promise<{finishedPath?:string, mask?:Float32Array, maskWidth?:number,
 *                    maskHeight?:number, beautyApplied:boolean, alphaOnly?:boolean}>}
 */
function cloudMatting(req, cloudSegment, cloudPrecheck) {
  const { imgPath, box, outW, outH, bgHex, alphaOnly } = req;
  const sizes = cloudProcessingSizes(box, outW, outH);
  if (typeof cloudPrecheck !== 'function') {
    return Promise.reject(new Error('内容安全检测服务未连接'));
  }
  const shoot = (sizeIndex, qualityIndex) => {
    const size = sizes[sizeIndex];
    const q = CLOUD_UPLOAD_QUALITY_LADDER[qualityIndex];
    return cropAndScale(imgPath, box, size.width, size.height, q)
      .then((path) => fileToBase64(path).then((b64) => {
        removeTempFile(path);
        return b64;
      }, (error) => {
        removeTempFile(path);
        throw error;
      }))
      .then((b64) => {
        if (b64.length <= CLOUD_PAYLOAD_LIMIT) return { b64, size, quality: q };
        if (qualityIndex + 1 < CLOUD_UPLOAD_QUALITY_LADDER.length) return shoot(sizeIndex, qualityIndex + 1);
        if (sizeIndex + 1 < sizes.length) return shoot(sizeIndex + 1, 0);
        throw new Error(`上传图过大（${Math.round(b64.length / 1024)}KB）`);
      });
  };
  let upload;
  // 两张图串行准备，避免低端机同时解码两份完整原图。检测通过才准备并上传工作图。
  return checkImageBase64(imgPath, CHECK_PAYLOAD_LIMIT)
    .then((checkImage) => {
      if (checkImage.length > CHECK_PAYLOAD_LIMIT) throw new Error('完整原图送检数据过大，请更换照片');
      console.log('[photo][流程] ' + STEP_BY_KEY.seccheck.seq + '/' + STEPS.length
        + ' 送检图=' + Math.round(checkImage.length / 1024) + 'KB');
      return cloudPrecheck(checkImage);
    })
    .then((res) => {
      if (!res || !res.ok || !res.checkTicket) {
        const err = new Error((res && res.msg) || '内容安全检测未通过');
        err.code = res && res.code;
        throw err;
      }
      return shoot(0, 0).then((prepared) => ({ prepared, checkTicket: res.checkTicket }));
    })
    .then(({ prepared, checkTicket }) => {
      upload = prepared;
      const extra = alphaOnly
        ? { alphaOnly: true }
        : { bgHex, outTop: outOfSourceTopRows(box, prepared.size.height) };
      extra.checkTicket = checkTicket;
      console.log('[photo][流程] ' + STEP_BY_KEY.matting.seq + '/' + STEPS.length
        + ' 抠图工作图=' + Math.round(prepared.b64.length / 1024)
        + 'KB（单次预算 ' + Math.round(CLOUD_BODY_BUDGET / 1024) + 'KB）');
      return cloudSegment(prepared.b64, extra);
    })
    .then((res) => {
      if (!res || !res.ok || (!res.image && !res.alpha)) {
        // 保留后端错误码（如 RISKY_CONTENT）——上层要按码决定「回退本地」还是「直接中断」
        const err = new Error((res && res.msg) || '云端精修不可用');
        err.code = res && res.code;
        throw err;
      }
      const beautyApplied = res.beautyApplied === true;
      const secChecked = res.secChecked === true;   // 内容安全检测已通过（审核整改可查证）
      // 只替换背景：云端只回 alpha 灰度图，端上用本地裁剪图自己换底
      //（走 buildComposite 的去污染 + 头发修复链，观感不降级、像素零损失）
      if (res.alpha) {
        return base64ToTempFile(res.alpha, 'png')
          .then(maskFromGrayPng)
          .then((m) => Object.assign(m, { beautyApplied: false, alphaOnly: true, secChecked }));
      }
      if (res.finished) {
        return base64ToTempFile(res.image, 'jpg').then((p) => ({
          finishedPath: p,
          beautyApplied,
          beautySkippedReason: res.beautySkippedReason,
          processingSize: upload.size,
          uploadQuality: upload.quality,
          secChecked,
        }));
      }
      // 不静默退回低分辨率旧链路，否则用户保存时会得到看似高清的模糊图。
      throw new Error('云端未返回高清成品，请稍后重试');
    });
}

/**
 * 制作一张证件照
 * @param {object} opts
 *   imgPath       原图路径（wx.chooseMedia original）
 *   sizeSpec      specs.js 的尺寸规格对象（px / sizeRule / minSizeKB / maxSizeKB /
 *                 cropFaceRatio）—— v0.5.0 起取代原来的 exam 对象
 *   bgHex         底色
 *   watermark     水印文字；传空串则不加水印（付费高清版）
 *   cloudSegment  必填，云端精修：(上传图 base64, {alphaOnly?, bgHex?, outTop}) =>
 *                 Promise<{ok, alpha?|image?, finished?, beautyApplied?, secChecked?}>；
 *                 失败一律 reject 上抛（不再回退端上 MODNet）
 *   alphaOnly     可选，true = 只替换背景（不美颜）：云端只回 alpha 灰度图，
 *                 端上用本地裁剪图换底（省流量、不经有损 JPEG）
 *   onStep        (step, info) 进度回调：face / crop / seccheck / matting / compose / size
 * @returns {Promise<object>} 结果（path/kb/quality/box/metrics/faceInfo/mattingMode）
 */
function makeIdPhoto(opts) {
  const o = opts || {};
  // v0.5.0：不再有「考试」对象，改为直接吃一个尺寸规格（specs.js 的 SIZES 条目）
  const spec = o.sizeSpec;
  if (!spec || !Array.isArray(spec.px)) {
    return Promise.reject(new Error('缺少尺寸规格'));
  }
  const outW = spec.px[0];
  const outH = spec.px[1];
  const ratio = outW / outH;
  const metrics = [];
  const t0 = Date.now();
  const notifyStep = o.onStep || function () {};
  // 每进入一个阶段：先按序号打一行流程日志（1/5 … 5/5），再通知页面推进步骤条。
  // 日志序号与步骤条同源于 STEPS，永远不会各说各话。
  let lastStepAt = t0;
  const step = function (key, info) {
    const now = Date.now();
    logStep(key, now - lastStepAt, now - t0);
    lastStepAt = now;
    notifyStep(key, info);
  };

  return detectFaceOriginal(o.imgPath)
    .then((fd) => {
      metrics.push({ k: '人脸检测', v: fd.costMs ? `${fd.costMs} ms` : '跳过' });
      // 构图的人脸占比：尺寸可覆盖（如驾驶证按 GA482-2012 头部占比更大），默认 0.45
      const faceRatio = spec.cropFaceRatio || 0.45;
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
      const t1 = Date.now();
      const finish = (r, mode) => {
        const ms = Date.now() - t1;
        // 内容安全检测（微信 security.mediaCheckAsync）由后端在调腾讯云之前完成，
        // 因此排在抠图之前。⚠️ 送检的是**未裁剪的完整原图**（checkImageBase64）——
        // 端上那步构图裁剪只是本地生成本地工作图，不会让裁剪框外的违规内容逃过检测。
        if (mode === 'cloud' && r.secChecked) {
          metrics.push({ k: '内容安全检测', v: '已通过（微信内容安全检测）' });
        }
        metrics.push({
          k: mode === 'cloud' ? '云端抠图' : '端上推理',
          v: mode === 'cloud' ? `${ms} ms（腾讯云 BDA）` : `${ms} ms（蒙版 ${r.maskWidth}×${r.maskHeight}）`,
        });
        if (mode === 'cloud') {
          if (r.processingSize) {
            metrics.push({ k: '处理分辨率', v: `${r.processingSize.width}×${r.processingSize.height}（上传质量 ${Math.round(r.uploadQuality * 100)}%）` });
          }
          if (r.alphaOnly) {
            // 只替换背景：云端只回 alpha，换底在端上完成（流量 10~30KB 级）
            metrics.push({ k: '云端回传', v: 'alpha 灰度图（端上换底）' });
          } else if (r.beautyApplied || r.beautySkippedReason) {
            metrics.push({ k: '局部美肤', v: r.beautyApplied ? '已处理' : (r.beautySkippedReason || '未生效') });
          }
        }
        return {
          fd,
          box,
          croppedPath,
          mask: r.mask,
          maskWidth: r.maskWidth,
          maskHeight: r.maskHeight,
          finishedPath: r.finishedPath,   // 云端成品图（有则不再走端上换底）
          processingSize: r.processingSize,
          mattingMs: ms,
          mattingMode: mode,
        };
      };
      // ============================================================
      // 证件照制作只有一条通道：云端精修（含内容安全检测）。
      // 已取消「云端失败静默回退端上抠图」——降级会让用户拿到不一致的成片，
      // 且会绕过内容安全检测。任何失败都直接抛出，由页面明确提示原因。
      // （端上 MODNet 推理保留在 matting.js 供实验室页自检使用，不再参与出片。）
      // ============================================================
      if (typeof o.cloudSegment !== 'function') {
        const err = new Error('云端处理服务未连接，请检查网络后重试');
        err.code = 'CLOUD_UNAVAILABLE';
        return Promise.reject(err);
      }
      // 真实时序（必须与后端内部顺序、结果页步骤顺序三者一致）：
      //   ① 上一步：端上本地按证件照规格构图裁剪（仅生成本地工作图，不上传）
      //   ② 完整原图（未裁剪）单独上传并等待微信内容安全检测结果
      //   ③ 检测通过后领取一次性凭证，再上传较高清的裁剪工作图
      //   ④ 服务端消费凭证后才把裁剪图交给腾讯云 BDA 做抠图
      // 所以「内容安全检测」必须排在「抠图」之前 —— 这正是后端内部的执行顺序。
      step('seccheck');
      return cloudMatting(
        {
          imgPath: o.imgPath,
          box,
          outW,
          outH,
          bgHex: o.bgHex || '#FFFFFF',
          outTop: outOfSourceTopRows(box, outH),
          // 只替换背景（不美颜）⇒ 只要 alpha，端上换底；美颜则走后端成品图模式
          alphaOnly: !!o.alphaOnly,
        },
        o.cloudSegment,
        o.cloudPrecheck
      )
        .then((r) => {
          step('matting');
          return finish(r, 'cloud');
        });
    })
    .then((ctx0) => {
      step('compose');
      const t2 = Date.now();
      // 合成干净的画布（无水印）；水印在导出阶段按版本叠加
      const wm = o.watermark === undefined ? '登科喵证件照 · 预览' : o.watermark;
      if (ctx0.finishedPath) {
        // 云端成品图已完成换底和边缘净化，端上保持其处理分辨率。
        const previewSize = ctx0.processingSize || { width: outW, height: outH };
        return canvasFromImage(ctx0.finishedPath, previewSize.width, previewSize.height).then((canvas) => {
          removeTempFile(ctx0.finishedPath);
          metrics.push({ k: '成品合成', v: '云端完成（含换底 / 边缘净化）' });
          metrics.push({ k: '高清成片', v: `${previewSize.width}×${previewSize.height}` });
          return Object.assign(ctx0, {
            canvas,
            exportWidth: previewSize.width,
            exportHeight: previewSize.height,
            wm,
          });
        });
      }
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
        sharpenCanvas(canvas, outW, outH);
        metrics.push({ k: '换底合成', v: `${Date.now() - t2} ms` });
        return Object.assign(ctx0, { canvas, wm });
      });
    })
    .then((ctx0) => {
      step('size');
      const width = ctx0.exportWidth || outW;
      const height = ctx0.exportHeight || outH;
      // 无水印高清图和带水印预览来自同一画布；不再缩到报名像素或按 KB 压缩。
      return exportCanvas(ctx0.canvas, width, height, 0.95).then((cleanPath) => {
        const kb = matting.fileSizeKB(cleanPath);
        metrics.push({ k: '高清导出', v: `${width}×${height} · ${kb} KB（无水印）` });
        let preview = Promise.resolve(cleanPath);
        if (ctx0.wm) {
          drawWatermark(ctx0.canvas.getContext('2d'), width, height, ctx0.wm);
          preview = exportCanvas(ctx0.canvas, width, height, 0.90);
        }
        return preview.then((previewPath) => {
          metrics.push({ k: '总计', v: `${Date.now() - t0} ms` });
          return {
            path: cleanPath,         // 保存的唯一成片：高清、无水印
            cleanPreviewPath: cleanPath,
            previewPath,             // 带水印预览（结果页展示 / 降级保存）
            kb,
            quality: 0.95,
            px: [width, height],
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
  STEPS,
  makeIdPhoto,
  detectFaceOriginal,
  cropAndScale,
  drawWatermark,
  exportCanvas,
  exportUnderSize,
};
