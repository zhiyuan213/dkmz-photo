/**
 * 证件照规格库（内置版）
 *
 * 数据源：docs/考试证件照规格库-v0.1.json（2026-10-10 核实自各考试官方公告）
 * 阶段 1 先内置随包下发；阶段 2+ 增加「云端下发 + 缓存」机制，便于紧急修正规格。
 *
 * ⚠️ 原则：宁可少一个有把握的模板，也不给一个错的规格。
 *    所有规格均须标注来源与更新时间，前端必须展示「以当次官方公告为准」的免责声明。
 *
 * ============================================================
 * v0.5.0 结构升级：**去掉「考试/场景」维度**
 *
 * 原来用户先选「考试」再进制作页，模板库里有 7 条考试规格，颜色与尺寸各自挂在
 * 考试下面。现在改为：用户**直接选底色 + 选尺寸**，不再有「选场景」这一步。
 *
 * 关键是**规格知识不能丢**。原考试库里的信息以两种方式保留下来：
 *   ① 每个尺寸带 usedBy 场景标注（如「国考 · 教资认定 · 四六级」）——
 *      用户选尺寸时仍能看到「这个尺寸是给谁用的」，而不是面对一串裸像素值；
 *   ② 每个尺寸的体积上限取**原场景里最严的那个**（如一寸取国考的 20~100KB），
 *      而不是取最宽松值 —— 宁可压得狠一点，也不能让用户因为超体积被驳回。
 *      「规格写错会让用户报名被驳回，比不提供更糟」，这是本库的第一原则。
 *
 * 注意 sizeNote 是给结果页直接展示的，必须写清「这个上限是怎么来的」。
 * ============================================================
 */

const META = {
  version: '0.5.0',
  updatedAt: '2026-10-14',
  /**
   * 结构版本：与后端 specs-seed.json 的 meta.schemaVersion 比对。
   * 不一致时后端会做一次全量重导（见 SpecService.syncSchemaIfNeeded），
   * 否则「库非空就跳过种子」的机制会让新结构永远进不了数据库。
   */
  schemaVersion: 2,
  disclaimer: '各省、各报考点要求可能逐年调整，请以当次官方报名公告为准。',
};

/**
 * 内置底色库（云端底色全部非法时的回退源）
 *
 * 红底（#B80202）于 2026-10-13 曾随「通用一寸 · 红底」模板一起下线；
 * 2026-10-14 结构升级去掉考试维度后**恢复**：底色不再依附于某个模板，
 * 用户可自由组合任意底色 × 任意尺寸，红底是档案 / 党团 / 纸质材料的常见需求。
 *
 * ⚠️ 恢复红底时必须同步移除后端 SpecService.RETIRED_IDS 里的 "red"，
 *    否则服务每次启动都会把红底从库里删掉，云端下发回来又变成 3 色。
 */
/**
 * 端上抠图模型信息（**由后端 /api/photo/specs 下发**，见 applyCloud）。
 *
 * 为什么要下发而不是写死在端上：模型 25MB 打不进小程序包（上限 2MB），只能运行时下载；
 * 下载地址一旦写死，换 CDN / 换模型就得重新发版审核（那正是 2026-10-15 线上事故的原因：
 * 地址写成了开发机局域网 IP http://192.168.21.11:5178，真机必然 ERR_CONNECTION_REFUSED）。
 * 改由后端下发后，服务端改环境变量 XPHOTO_MODEL_URL + 重启即可，端上不用动。
 */
const MODEL = {
  fileName: 'modnet-fp32.onnx',
  url: '', // 空 = 服务端未下发；本地联调用 utils/matting.js 的 DEV_MODEL_BASE
};

const BUILTIN_BACKGROUNDS = {
  white: { id: 'white', name: '白底', hex: '#FFFFFF' },
  blue: { id: 'blue', name: '蓝底', hex: '#438EDB' },
  lightBlue: { id: 'lightBlue', name: '浅蓝底', hex: '#64C5FF' },
  red: { id: 'red', name: '红底', hex: '#B80202' },
};

/** 底色展示顺序（BACKGROUNDS 是 map，选择器需要有序数组） */
const BUILTIN_BG_ORDER = ['white', 'blue', 'lightBlue', 'red'];

const BACKGROUNDS = Object.assign({}, BUILTIN_BACKGROUNDS);

/** 底色有序列表（供底色选择器渲染） */
let BG_ORDER = BUILTIN_BG_ORDER.slice();

/** 把 BACKGROUNDS 恢复为内置库（云端数据不可用时用） */
function seedBuiltinBackgrounds() {
  Object.keys(BACKGROUNDS).forEach((k) => delete BACKGROUNDS[k]);
  Object.keys(BUILTIN_BACKGROUNDS).forEach((k) => {
    BACKGROUNDS[k] = Object.assign({}, BUILTIN_BACKGROUNDS[k]);
  });
  BG_ORDER = BUILTIN_BG_ORDER.slice();
}

/** 按展示顺序返回底色数组 */
function allBackgrounds() {
  const list = BG_ORDER.map((id) => BACKGROUNDS[id]).filter(Boolean);
  // 云端可能下发了顺序表里没有的底色：追加到末尾，保证不漏
  Object.keys(BACKGROUNDS).forEach((id) => {
    if (BG_ORDER.indexOf(id) < 0) list.push(BACKGROUNDS[id]);
  });
  return list;
}

/**
 * 内置尺寸库。
 *
 * sizeRule: 'max'   只约束上限（maxSizeKB）
 *           'range' 区间约束（minSizeKB ~ maxSizeKB）
 *           'none'  无体积约束
 *
 * usedBy   : 场景标注。**这就是原考试库留下来的规格知识**，用户选尺寸时能看到
 *           「这个尺寸是干什么用的」，不必自己判断。
 * sizeNote : 结果页直接展示的体积说明，写清上限来源。
 * cropFaceRatio: 构图时人脸高度占画面高度的比例（越小留白越多）。默认 0.45；
 *           驾驶证按 GA482-2012 需要更大的头部占比，取 0.5。
 */
const BUILTIN_SIZES = [
  {
    id: 'one-inch',
    name: '一寸',
    mm: [25, 35],
    px: [295, 413],
    // 原场景：国考 20~100KB / 教资认定 ≤190KB / 四六级 20~200KB → 取最严 = 国考
    sizeRule: 'range',
    minSizeKB: 20,
    maxSizeKB: 100,
    sizeNote: '20~100KB（取国考最严要求，可通用于教资认定、四六级）',
    usedBy: ['国考', '教资认定', '四六级'],
  },
  {
    id: 'two-inch',
    name: '二寸',
    mm: [35, 49],
    px: [413, 579],
    sizeRule: 'max',
    maxSizeKB: 500,
    sizeNote: '≤500KB（通用，无官方硬性限制）',
    usedBy: ['通用', '纸质材料'],
  },
  {
    id: 'small-one-inch',
    name: '小一寸',
    mm: [22, 32],
    px: [260, 378],
    sizeRule: 'max',
    maxSizeKB: 500,
    sizeNote: '≤500KB（通用，无官方硬性限制）',
    usedBy: ['通用'],
  },
  {
    id: 'large-one-inch',
    name: '大一寸',
    mm: [33, 48],
    px: [390, 567],
    sizeRule: 'max',
    maxSizeKB: 500,
    sizeNote: '≤500KB（通用，无官方硬性限制）',
    usedBy: ['通用'],
  },
  {
    id: 'small-two-inch',
    name: '小二寸',
    mm: [35, 45],
    px: [413, 531],
    sizeRule: 'max',
    maxSizeKB: 500,
    sizeNote: '≤500KB（通用，无官方硬性限制）',
    usedBy: ['通用'],
  },
  {
    id: 'large-two-inch',
    name: '大二寸',
    mm: [35, 53],
    px: [413, 626],
    sizeRule: 'max',
    maxSizeKB: 500,
    sizeNote: '≤500KB（通用，无官方硬性限制）',
    usedBy: ['通用'],
  },
  {
    id: 'kaoyan-3-4',
    name: '考研 3:4',
    mm: null,
    px: [480, 640],
    // 原场景：考研只约束比例（3:4）与「建议不超过 10M」
    sizeRule: 'max',
    maxSizeKB: 10240,
    sizeNote: '建议不超过 10M（研招网各报考点表述）',
    usedBy: ['考研网上确认'],
  },
  {
    id: 'ntce-written',
    name: '教资笔试',
    mm: null,
    px: [300, 400],
    sizeRule: 'max',
    maxSizeKB: 200,
    sizeNote: '≤200KB，压缩品质系数不低于 60',
    usedBy: ['教师资格笔试报名'],
  },
  {
    id: 'driving-license',
    name: '驾驶证',
    mm: null,
    px: [520, 756],
    // GA482-2012：头部（发顶到下巴）约占照片高度 2/3 → 人脸占比取 0.5
    sizeRule: 'max',
    maxSizeKB: 300,
    sizeNote: '≤300KB（交管12123 电子驾照要求）',
    usedBy: ['驾驶证', '电子驾照'],
    cropFaceRatio: 0.5,
  },
  {
    id: 'cet-192',
    name: '四六级小规格',
    mm: [33, 48],
    px: [144, 192],
    sizeRule: 'range',
    minSizeKB: 20,
    maxSizeKB: 200,
    sizeNote: '20~200KB（部分院校更严，以本校通知为准）',
    usedBy: ['四六级'],
  },
];

const SIZES = BUILTIN_SIZES.map((s) => Object.assign({}, s));

// ------------------------------------------------------------------ 辅助函数

function allSizes() {
  return SIZES;
}

function getSize(id) {
  return SIZES.find((s) => s.id === id) || null;
}

function getBackground(id) {
  return BACKGROUNDS[id] || BACKGROUNDS.white;
}

/** 尺寸摘要一句话，用于选择卡片：像素 · 体积 */
/**
 * 尺寸摘要一行文案。
 * @param {object} size 尺寸项
 * @param {boolean} [compact] 紧凑模式：省略毫米尺寸（首页卡片窄，毫米属次要信息，制作页仍会展示）
 */
function sizeSummary(size, compact) {
  const px = `${size.px[0]}×${size.px[1]}`;
  let vol = '';
  if (size.sizeRule === 'max') {
    vol = `≤${size.maxSizeKB >= 1024 ? size.maxSizeKB / 1024 + 'M' : size.maxSizeKB + 'K'}`;
  } else if (size.sizeRule === 'range') {
    vol = `${size.minSizeKB}~${size.maxSizeKB}K`;
  }
  const mm = !compact && Array.isArray(size.mm) ? ` · ${size.mm[0]}×${size.mm[1]}mm` : '';
  return `${px}${mm} · ${vol}`;
}

/** 场景标注一句话（卡片副标题） */
function sizeUsage(size) {
  return (size.usedBy || []).join(' · ');
}

// ============================================================
// 云端规格库（阶段 2）
//
// 为什么要云端下发：规格写错会让用户报名被驳回（比不提供更糟），
// 出问题必须几分钟内改掉，而不是等小程序发版审核。
//
// 三条防线：
//   ① 随包内置库（本文件）—— 永远可用，作为兜底
//   ② 本地缓存 —— 上次拉到的云端版本，秒开且离线可用
//   ③ 云端下发 —— 启动时静默拉取，有更新才替换内存中的库
// 云端数据一律先校验格式，不合格就**不用**（宁可用旧版，也不能用坏数据）。
// ============================================================

// v2：结构从 exams/presets 改为 sizes，缓存键必须升版，
// 否则老缓存（只有 exams、没有 sizes）会一直覆盖不了，拖到 TTL 才失效。
const CACHE_KEY = 'photo_specs_cache_v2';
const CACHE_TTL = 24 * 3600 * 1000; // 24h：超过则即使版本没变也重新拉一次（防缓存被写坏）

/** 已应用的库来源：builtin / cache / cloud（「关于」页展示用） */
const SOURCE = { value: 'builtin', version: META.version, updatedAt: META.updatedAt };

/** 云端单条尺寸的格式校验：字段缺失/类型不对一律判为不合格 */
function validSize(s) {
  if (!s || typeof s !== 'object') return false;
  if (!s.id || !s.name) return false;
  if (!Array.isArray(s.px) || s.px.length !== 2) return false;
  if (!s.sizeRule) return false;
  return true;
}

/** rgb[3] -> '#RRGGBB'（大写，与内置库写法一致；越界/非数字按 0 处理，绝不产出非法 hex） */
function rgbToHex(rgb) {
  const n = (v) => Math.max(0, Math.min(255, Math.round(Number(v) || 0))).toString(16).padStart(2, '0');
  return ('#' + n(rgb[0]) + n(rgb[1]) + n(rgb[2])).toUpperCase();
}

/**
 * 背景色归一化：云端库的背景是 { id, name, rgb:[r,g,b] }，而前端整条链路
 * （色块渲染 item.hex、导出 bg.hex、pipeline.hexToRgb）用的都是 hex 字符串。
 * 在这个唯一入口把 rgb 换算成 hex 补上，否则色块全白、导出底色丢失。
 * （内置库自带 hex；换算结果与内置值逐一相等，见 specs-seed.json。）
 */
function normalizeBackground(b) {
  if (!b || !b.id) return null;
  const bg = Object.assign({}, b);
  if (!bg.hex && Array.isArray(bg.rgb) && bg.rgb.length === 3) {
    bg.hex = rgbToHex(bg.rgb);
  }
  // hex 都没有的条目直接丢弃：宁缺勿错，别让非法数据进 BACKGROUNDS
  return bg.hex ? bg : null;
}

/** 把云端下发体应用到内存（原地替换，保证其它页面已持有的引用也能生效） */
function applyCloud(doc) {
  if (!doc || doc.ok === false) return false;
  const sizes = (Array.isArray(doc.sizes) ? doc.sizes : []).filter(validSize);
  // 防御：云端尺寸列表为空或全部不合格时，保留内置库（否则首页会变空白）
  if (!sizes.length) {
    console.warn('[photo][specs] 云端规格库无有效数据，保留现有库');
    return false;
  }
  SIZES.length = 0;
  sizes.forEach((s) => SIZES.push(s));

  if (Array.isArray(doc.backgrounds) && doc.backgrounds.length) {
    Object.keys(BACKGROUNDS).forEach((k) => delete BACKGROUNDS[k]);
    const order = [];
    doc.backgrounds.forEach((b) => {
      const bg = normalizeBackground(b);
      if (bg) {
        BACKGROUNDS[bg.id] = bg;
        order.push(bg.id);
      }
    });
    // 防御：云端背景全部非法时回退内置库（否则底色选择区会空掉）
    if (!order.length) {
      console.warn('[photo][specs] 云端背景数据全部无效，回退内置背景');
      seedBuiltinBackgrounds();
    } else {
      BG_ORDER = order;
    }
  }
  // 模型下载信息：**云端是唯一事实来源**。明确给了 model 对象就整体采用——
  // url 为空 = 服务端未配置（或刚删掉环境变量），要清掉端上残留的旧地址；
  // 不能「只在有新值时覆盖」，否则删掉变量后端上还会拿着旧地址继续撞墙。
  if (doc.model && typeof doc.model === 'object') {
    MODEL.url = doc.model.url ? String(doc.model.url).trim() : '';
    if (doc.model.fileName) MODEL.fileName = String(doc.model.fileName).trim();
  }

  if (doc.version) META.version = doc.version;
  if (doc.updatedAt) META.updatedAt = doc.updatedAt;
  if (doc.disclaimer) META.disclaimer = doc.disclaimer;
  if (doc.product) META.product = doc.product;
  if (Array.isArray(doc.verifiedFrom) && doc.verifiedFrom.length) META.verifiedFrom = doc.verifiedFrom;

  SOURCE.value = doc.__from || 'cloud';
  SOURCE.version = META.version;
  SOURCE.updatedAt = META.updatedAt;
  return true;
}

/** 读取本地缓存的云端库（同步，启动即可用） */
function loadCache() {
  try {
    const c = wx.getStorageSync(CACHE_KEY);
    if (c && c.doc) {
      const applied = applyCloud(Object.assign({}, c.doc, { __from: 'cache' }));
      if (applied) {
        console.log('[photo][specs] 已应用缓存版本', c.doc.version, '缓存于', new Date(c.at).toLocaleString());
        return true;
      }
    }
  } catch (e) {
    console.warn('[photo][specs] 读缓存失败', e);
  }
  return false;
}

function saveCache(doc) {
  try {
    wx.setStorageSync(CACHE_KEY, { at: Date.now(), doc });
  } catch (e) {
    console.warn('[photo][specs] 写缓存失败', e);
  }
}

/**
 * 静默刷新云端规格库（启动 / 下拉刷新时调用）。
 * 先比版本号，只有真的变了才拉全量，省流量也省后端。
 * @returns {Promise<{ok:boolean, updated:boolean, version?:string, reason?:string}>}
 */
function refresh() {
  const api = require('./api.js');
  if (!api.isCloudEnabled()) {
    return Promise.resolve({ ok: false, updated: false, reason: 'NO_CHANNEL' });
  }
  return api
    .fetchSpecVersion()
    .then((v) => {
      if (!v || v.ok === false) {
        return { ok: false, updated: false, reason: 'VERSION_FAIL' };
      }
      const cached = (() => {
        try {
          return wx.getStorageSync(CACHE_KEY);
        } catch (e) {
          return null;
        }
      })();
      const stale = !cached || Date.now() - (cached.at || 0) > CACHE_TTL;
      // 模型地址变了也要全量拉：XPHOTO_MODEL_URL 是服务端随时可改的配置，
      // 不能被「规格版本号没变」的快速路径挡住，否则端上会一直用缓存里的旧地址
      const cachedModelUrl =
        (cached && cached.doc && cached.doc.model && cached.doc.model.url) || '';
      const modelChanged = typeof v.modelUrl === 'string' && v.modelUrl !== cachedModelUrl;
      if (v.version && v.version === META.version && !stale && !modelChanged) {
        console.log('[photo][specs] 云端版本一致，跳过拉取', v.version);
        return { ok: true, updated: false, version: v.version };
      }
      return api.fetchSpecs().then((doc) => {
        if (applyCloud(doc)) {
          saveCache(doc);
          console.log('[photo][specs] 已更新到云端版本', doc.version, '尺寸数', (doc.sizes || []).length);
          return { ok: true, updated: true, version: doc.version };
        }
        return { ok: false, updated: false, reason: 'INVALID_DOC' };
      });
    })
    .catch((err) => {
      // 云端不可用绝不能影响使用：静默降级到内置/缓存库
      console.warn('[photo][specs] 云端刷新失败，继续用现有库', err && (err.msg || err.errMsg || err));
      return { ok: false, updated: false, reason: (err && err.code) || 'FAIL' };
    });
}

/** 当前库来源信息（「关于」页展示：让用户知道规格是什么时候核实的） */
function sourceInfo() {
  return {
    source: SOURCE.value,
    sourceText:
      SOURCE.value === 'cloud' ? '云端规格库' : SOURCE.value === 'cache' ? '云端规格库（本地缓存）' : '内置规格库',
    version: META.version,
    updatedAt: META.updatedAt,
  };
}

/**
 * 端上模型信息（供 utils/matting.js 取下载地址）。
 * url 可能为空 —— 调用方必须自己兜底，不要假设它一定有值。
 */
function modelInfo() {
  return { fileName: MODEL.fileName, url: MODEL.url };
}

module.exports = {
  META,
  MODEL,
  BACKGROUNDS,
  SIZES,
  allBackgrounds,
  allSizes,
  getSize,
  getBackground,
  sizeSummary,
  sizeUsage,
  refresh,
  loadCache,
  sourceInfo,
  modelInfo,
};
