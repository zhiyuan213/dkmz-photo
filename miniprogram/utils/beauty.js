/**
 * 证件照美化（照相馆式修图）—— 纯端上像素运算，无额外模型依赖
 *
 * 三个维度，各 3 档（1 轻 / 2 中 / 3 强）：
 *   whiten 美白：频率分离——低频（肤色底子）白平衡去黄 + 提亮 + 降饱和，
 *                高频（五官细节）原样保留 → 白而有轮廓，不是「白雾脸」。
 *                参数按「最美证件照」参照物量化标定（Y≈185/黄度≈27/R-B≈44）
 *   smooth 磨皮：保边平滑（guided filter）+ 频率分离祛痘
 *   hair   黑发：白发（高亮 + 低饱和）向深灰收缩，黑发完全不动
 *
 * 作用区域（v6 定稿，踩坑迭代 6 版的结论）：
 *   - 像素级「肤色判据」做 mask 在高光/阴影区天然不命中 → 脸上出白斑块，已废弃
 *   - 改用「几何分区」：脸框（发际线以下）+ 脖子带（横向收窄），大羽化
 *   - 深色保护：眉毛/眼睛/眼镜框（Y<90）按亮度平滑衰减，特征不变浅
 *   - 脖子带叠加「色度相似度」权重（Cr-Cb>28 全权重）：米白/卡其衣领的
 *     Cr-Cb≈18 会被压到 0，避免衣领被误美白成橙色
 *   - 全程乘人像 alpha（MODNet），背景不受影响
 *
 * 合规红线：仅做光度与肤质修正，绝不改变面部几何特征
 * （不瘦脸、不大眼、不换脸）—— 证件照真实性要求。
 *
 * 依赖调用方提供人像 alpha（MODNet 输出）与输出坐标系下的人脸框。
 * 原图全程不出手机。
 */

// ---------------------------------------------------------------- 档位参数
// 下标 = 档位：0=关、1=轻、2=中、3=强。
// 美白参数以「最美证件照」参照物标定（量化：亮度Y≈185 / 黄度≈27 / R-B≈44）：
//   轻 Y≈164、中 Y≈180（=参照物水平，默认）、强 Y≈190。

/** 美白：k = 提亮系数；wb = 白平衡强度（灰世界比例）；dy = 去黄强度（B+ R/G-）；dsat = 降饱和（收敛红调） */
const WHITEN_K = [1.0, 0.62, 0.5, 0.42];
const WHITEN_WB = [0, 0.3, 0.35, 0.4];
const WHITEN_DY = [0, 6, 8, 10];
const WHITEN_DSAT = [0, 0, 0.05, 0.1];

/** 磨皮：r = 滤波半径；eps = 保边阈值（越大越平滑）；blemish = 祛痘强度（0~255 高频截断） */
const SMOOTH_R = [0, 3, 5, 7];
const SMOOTH_EPS = [0, 0.0022, 0.004, 0.0062];
const SMOOTH_BLEMISH = [0, 5, 8, 12];

/** 黑发：amount = 向目标灰度收缩的比例（越大越黑） */
const HAIR_AMOUNT = [0, 0.45, 0.62, 0.8];
const HAIR_TARGET = 110;
const HAIR_V_FLOOR = 100;
const HAIR_V_SPAN = 160;

/** 深色特征保护阈值：亮度低于此值的像素（眉毛/眼镜框）美白权重线性衰减 */
const PROTECT_Y = 90;

/** 档位展示文案（供页面渲染） */
const LEVEL_LABELS = ['轻', '中', '强'];

// ---------------------------------------------------------------- 基础算子

/** 单通道可分离盒式模糊（滑动窗口 O(n)），radius >= 1；跑两遍近似高斯 */
function blur1(src, w, h, r) {
  const n = w * h;
  const tmp = new Float32Array(n);
  const out = new Float32Array(n);
  const win = 2 * r + 1;
  let x;
  let y;
  let i;
  let s;

  for (y = 0; y < h; y++) {
    const row = y * w;
    s = 0;
    for (i = -r; i <= r; i++) s += src[row + (i < 0 ? 0 : i > w - 1 ? w - 1 : i)];
    for (x = 0; x < w; x++) {
      tmp[row + x] = s / win;
      const xo = x - r < 0 ? 0 : x - r;
      const xi = x + r + 1 > w - 1 ? w - 1 : x + r + 1;
      s += src[row + xi] - src[row + xo];
    }
  }
  for (x = 0; x < w; x++) {
    s = 0;
    for (i = -r; i <= r; i++) {
      const yy = i < 0 ? 0 : i > h - 1 ? h - 1 : i;
      s += tmp[yy * w + x];
    }
    for (y = 0; y < h; y++) {
      out[y * w + x] = s / win;
      const yo = y - r < 0 ? 0 : y - r;
      const yi = y + r + 1 > h - 1 ? h - 1 : y + r + 1;
      s += tmp[yi * w + x] - tmp[yo * w + x];
    }
  }
  return out;
}

function blur2(src, w, h, r) {
  return blur1(blur1(src, w, h, r), w, h, r);
}

/**
 * 美白/磨皮共用 mask（0~1）：
 *   脸区（发际线→下巴，全脸框宽）+ 脖子带（横向收窄 18%~82%），
 *   各自羽化后相加；深色特征保护（Y<90 衰减）× 人像 alpha。
 * face 缺失时退化为「上半部 55% 高度」的保守区域（兜底构图也能用）。
 */
function buildBeautyMask(data, w, h, alpha, face) {
  const n = w * h;
  const fx = face ? face.left : 0;
  const fy = face ? face.top : 0;
  const fw = face ? face.width : w;
  const fh = face ? face.height : h * 0.5;

  const hairline = Math.max(0, Math.round(fy + fh * 0.16));
  const chin = Math.min(h, Math.round(fy + fh));
  const neckEnd = Math.min(h, Math.round(fy + fh * 1.28));
  const nx0 = Math.max(0, Math.round(fx + fw * 0.18));
  const nx1 = Math.min(w, Math.round(fx + fw * 0.82));
  // 无脸框兜底：区域推到画面上半部
  const faceTop = face ? hairline : 0;
  const faceBottom = face ? chin : Math.round(h * 0.55);
  const neckBottom = face ? neckEnd : Math.round(h * 0.72);

  // 硬区域（face=1 / neck=1 分开记录，羽化半径不同）
  const faceHard = new Float32Array(n);
  const neckHard = new Float32Array(n);
  for (let y = faceTop; y < faceBottom; y++) {
    const row = y * w;
    for (let x = Math.max(0, Math.round(fx)); x < Math.min(w, Math.round(fx + fw)); x++) faceHard[row + x] = 1;
  }
  for (let y = faceBottom; y < neckBottom; y++) {
    const row = y * w;
    for (let x = nx0; x < nx1; x++) neckHard[row + x] = 1;
  }

  const faceSoft = face ? blur2(faceHard, w, h, Math.max(2, Math.round(fw * 0.06))) : faceHard;
  const neckSoft = blur2(neckHard, w, h, Math.max(2, Math.round(fw * 0.03)));

  const mask = new Float32Array(n);
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    const r = data[p];
    const g = data[p + 1];
    const b = data[p + 2];
    const Y = 0.299 * r + 0.587 * g + 0.114 * b;
    const protect = Y < PROTECT_Y ? Y / PROTECT_Y : 1; // 深色特征保护
    let m = faceSoft[i] + neckSoft[i];
    if (m > 1) m = 1;
    m *= protect;
    if (alpha) m *= alpha[i] > 0.6 ? 1 : alpha[i] / 0.6;
    mask[i] = m;
  }

  // 脖子带叠加「色度相似度」：衣领（Cr-Cb≈18）权重归零，皮肤（≈40）全权重
  if (face) {
    for (let y = faceBottom; y < neckBottom; y++) {
      const row = y * w;
      for (let x = nx0; x < nx1; x++) {
        const i = row + x;
        if (neckSoft[i] <= 0.01) continue;
        const p = i * 4;
        const r = data[p];
        const g = data[p + 1];
        const b = data[p + 2];
        const cb = -0.169 * r - 0.331 * g + 0.5 * b + 128;
        const cr = 0.5 * r - 0.419 * g - 0.081 * b + 128;
        const skinLike = (cr - cb - 20) / 15;
        mask[i] *= skinLike > 1 ? 1 : skinLike < 0 ? 0 : skinLike;
      }
    }
  }
  return mask;
}

// ---------------------------------------------------------------- 各处理步骤

/** 磨皮 + 祛痘：guided filter（亮度引导，保边）+ 高频截断去斑 */
function stepSmooth(data, w, h, n, mask, level) {
  const r = SMOOTH_R[level];
  if (!r) return;
  const eps = SMOOTH_EPS[level];
  const blemish = SMOOTH_BLEMISH[level];

  const Y = new Float32Array(n);
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    Y[i] = (0.299 * data[p] + 0.587 * data[p + 1] + 0.114 * data[p + 2]) / 255;
  }

  const meanY = blur1(Y, w, h, r);
  const sq = new Float32Array(n);
  for (let i = 0; i < n; i++) sq[i] = Y[i] * Y[i];
  const corrY = blur1(sq, w, h, r);
  const a = new Float32Array(n);
  const b = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const m = meanY[i];
    const v = corrY[i] - m * m;
    const ai = v / (v + eps);
    a[i] = ai;
    b[i] = m * (1 - ai);
  }
  const meanA = blur1(a, w, h, r);
  const meanB = blur1(b, w, h, r);

  const Ys = new Float32Array(n);
  for (let i = 0; i < n; i++) Ys[i] = meanA[i] * Y[i] + meanB[i];

  if (blemish > 0) {
    const low = blur1(Ys, w, h, Math.max(2, r));
    const T = blemish / 255;
    for (let i = 0; i < n; i++) {
      const hi = Ys[i] - low[i];
      const clipped = hi > T ? T : hi < -T ? -T : hi;
      Ys[i] = low[i] + clipped;
    }
  }

  for (let i = 0, p = 0; i < n; i++, p += 4) {
    const dY = (Ys[i] - Y[i]) * 255 * mask[i];
    if (dY === 0) continue;
    data[p] += dY;
    data[p + 1] += dY;
    data[p + 2] += dY;
  }
}

/**
 * 美白（v8 频率分离版，按「最美证件照」参照物标定）：
 *   低频（肤色底子）：白平衡去黄 + Y soft-clip 提亮 + RGB 去黄 + 轻度降饱和
 *   高频（五官阴影/轮廓细节）：原样加回，不提亮
 * 解决整图提亮的「白雾脸」问题——参照物是「白但有妆感、有轮廓」，本质是
 * 只提底子、保住细节层对比度。
 */
function stepWhiten(data, w, h, n, mask, face, level) {
  const k = WHITEN_K[level];
  if (k >= 1) return;
  const wb = WHITEN_WB[level];
  const dy = WHITEN_DY[level];
  const dsat = WHITEN_DSAT[level];

  // ---- 频率分离：base = 大半径模糊（~6% 脸宽）；detail = 原图 - base ----
  const rBase = Math.max(3, Math.round((face ? face.width : Math.min(w, h) * 0.4) * 0.06));
  const bR = new Float32Array(n);
  const bG = new Float32Array(n);
  const bB = new Float32Array(n);
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    bR[i] = data[p];
    bG[i] = data[p + 1];
    bB[i] = data[p + 2];
  }
  const baseR = blur2(bR, w, h, rBase);
  const baseG = blur2(bG, w, h, rBase);
  const baseB = blur2(bB, w, h, rBase);

  // 白平衡 gain（base 层 mask>0.55 的灰世界统计）
  let sb = 0;
  let sg = 0;
  let sr = 0;
  let cnt = 0;
  for (let i = 0; i < n; i++) {
    if (mask[i] > 0.55) {
      sb += baseB[i];
      sg += baseG[i];
      sr += baseR[i];
      cnt++;
    }
  }
  let gb = 1;
  let gg = 1;
  let gr = 1;
  if (cnt > 50) {
    const gray = (sb + sg + sr) / (3 * cnt);
    gb = 1 + (gray / (sb / cnt) - 1) * wb;
    gg = 1 + (gray / (sg / cnt) - 1) * wb;
    gr = 1 + (gray / (sr / cnt) - 1) * wb;
  }

  for (let i = 0, p = 0; i < n; i++, p += 4) {
    const wgt = mask[i];
    if (wgt <= 0.01) continue;
    // base 层做美白
    let r = baseR[i] * gr;
    let g = baseG[i] * gg;
    let b = baseB[i] * gb;
    const Y = 0.299 * r + 0.587 * g + 0.114 * b;
    const Y2 = 255 - (255 - Y) * k;
    const ratio = Y > 1 ? Y2 / Y : 1;
    r *= ratio;
    g *= ratio;
    b *= ratio;
    b += dy;
    r -= dy * 0.4;
    g -= dy * 0.4;
    if (dsat > 0) {
      const mean = (r + g + b) / 3;
      r += (mean - r) * dsat;
      g += (mean - g) * dsat;
      b += (mean - b) * dsat;
    }
    // 高频细节原样加回（原图 - base，未经美白 → 保住五官立体感）
    r += data[p] - baseR[i];
    g += data[p + 1] - baseG[i];
    b += data[p + 2] - baseB[i];
    r = r < 0 ? 0 : r > 255 ? 255 : r;
    g = g < 0 ? 0 : g > 255 ? 255 : g;
    b = b < 0 ? 0 : b > 255 ? 255 : b;
    data[p] += (r - data[p]) * wgt;
    data[p + 1] += (g - data[p + 1]) * wgt;
    data[p + 2] += (b - data[p + 2]) * wgt;
  }
}

/** 黑发：白发（高亮 + 低饱和）向目标灰度收缩，黑发完全不动 */
function stepHair(data, w, h, n, alpha, face, level) {
  const amountMax = HAIR_AMOUNT[level];
  if (!amountMax) return;

  // 头部区域：发际线以上全宽 + 两鬓（收窄，避开眉毛上方皮肤）
  let head = null;
  if (face) {
    head = new Float32Array(n);
    const hairLine = Math.max(0, Math.round(face.top + face.height * 0.16));
    for (let y = 0; y < hairLine; y++) {
      const row = y * w;
      for (let x = 0; x < w; x++) head[row + x] = 1;
    }
    const sideW = Math.round(face.width * 0.22);
    const y0 = Math.max(0, Math.round(face.top));
    const y1 = Math.min(h, Math.round(face.top + face.height * 0.32));
    const lx0 = Math.max(0, Math.round(face.left - sideW));
    const lx1 = Math.round(face.left);
    const rx0 = Math.round(face.left + face.width);
    const rx1 = Math.min(w, Math.round(face.left + face.width + sideW));
    for (let y = y0; y < y1; y++) {
      const row = y * w;
      for (let x = lx0; x < lx1; x++) head[row + x] = 1;
      for (let x = rx0; x < rx1; x++) head[row + x] = 1;
    }
  }

  for (let i = 0, p = 0; i < n; i++, p += 4) {
    const r = data[p];
    const g = data[p + 1];
    const b = data[p + 2];
    const v = r > g ? (r > b ? r : b) : g > b ? g : b;
    if (v <= HAIR_V_FLOOR) continue; // 黑发：不动

    const mn = r < g ? (r < b ? r : b) : g < b ? g : b;
    const s = ((v - mn) / v) * 255;

    let wgt = alpha ? (alpha[i] > 0.6 ? 1 : alpha[i] / 0.6) : 1;
    if (head) wgt *= head[i];
    if (wgt <= 0) continue;
    wgt *= s < 120 ? 1 : s < 150 ? (150 - s) / 30 : 0;
    if (wgt <= 0) continue;

    const t = (v - HAIR_V_FLOOR) / HAIR_V_SPAN;
    const tt = t > 1 ? 1 : t;
    const amt = amountMax * tt * tt * wgt; // 平方：亮部（真白发）压得多，过渡自然
    if (amt <= 0.001) continue;

    const vNew = v - (v - HAIR_TARGET) * amt;
    const f = vNew / v;
    data[p] = r * f;
    data[p + 1] = g * f;
    data[p + 2] = b * f;
  }
}

// ---------------------------------------------------------------- 主入口

/**
 * 对「裁剪后的人像图」做美化（就地修改 data）
 *
 * @param {Uint8ClampedArray} data  RGBA 像素（来自 getImageData，长度 = w*h*4）
 * @param {number} w
 * @param {number} h
 * @param {object} opt
 *   alpha  {Float32Array|null} 人像 alpha（长度 w*h，0~1），来自 MODNet
 *   face   {object|null} 输出坐标系下的脸框 {left, top, width, height}
 *   whiten {number} 美白档位 0~3
 *   smooth {number} 磨皮档位 0~3
 *   hair   {number} 黑发档位 0~3
 * @returns {object} 各步骤耗时（ms），便于 lab 页观测
 */
function applyBeauty(data, w, h, opt) {
  const o = opt || {};
  const n = w * h;
  const t = {};
  const whiten = Math.min(3, Math.max(0, o.whiten | 0));
  const smooth = Math.min(3, Math.max(0, o.smooth | 0));
  const hair = Math.min(3, Math.max(0, o.hair | 0));
  const alpha = o.alpha || null;

  if (!whiten && !smooth && !hair) return t;

  let mask = null;
  if (whiten || smooth) {
    const t0 = now();
    mask = buildBeautyMask(data, w, h, alpha, o.face || null);
    t.mask = now() - t0;
  }

  if (smooth) {
    const t0 = now();
    stepSmooth(data, w, h, n, mask, smooth);
    t.smooth = now() - t0;
  }
  if (whiten) {
    const t0 = now();
    stepWhiten(data, w, h, n, mask, o.face || null, whiten);
    t.whiten = now() - t0;
  }
  if (hair) {
    const t0 = now();
    stepHair(data, w, h, n, alpha, o.face || null, hair);
    t.hair = now() - t0;
  }
  t.total = (t.mask || 0) + (t.smooth || 0) + (t.whiten || 0) + (t.hair || 0);
  return t;
}

function now() {
  return Date.now();
}

module.exports = {
  applyBeauty,
  LEVEL_LABELS,
  OPTION_DEFS: [
    { key: 'whiten', name: '美白', desc: '提亮肤色、去黄透亮' },
    { key: 'smooth', name: '磨皮', desc: '平滑肤质、淡化痘印' },
    { key: 'hair', name: '黑发', desc: '白发压深、保留层次' },
  ],
};
