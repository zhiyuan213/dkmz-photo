/**
 * 考试证件照规格库（内置版 v0.3）
 *
 * 数据源：docs/考试证件照规格库-v0.1.json（2026-10-10 核实自各考试官方公告）
 * 阶段 1 先内置随包下发；阶段 2+ 增加「云端下发 + 缓存」机制，便于紧急修正规格。
 *
 * ⚠️ 原则：宁可少一个有把握的模板，也不给一个错的规格。
 *    所有规格均须标注来源与更新时间，前端必须展示「以当次官方公告为准」的免责声明。
 */

const META = {
  version: '0.3.0',
  updatedAt: '2026-10-11',
  disclaimer:
    '各省、各报考点、各院校可能进一步细化要求，且逐年可能调整。本产品按官方公告预置模板，请以当次官方公告为准。',
};

/** 内置背景库（云端背景全部非法时的回退源；红底 #B80202 为实拍比对定稿值） */
const BUILTIN_BACKGROUNDS = {
  white: { id: 'white', name: '白底', hex: '#FFFFFF' },
  blue: { id: 'blue', name: '蓝底', hex: '#438EDB' },
  lightBlue: { id: 'lightBlue', name: '浅蓝底', hex: '#64C5FF' },
  red: { id: 'red', name: '红底', hex: '#B80202' },
};

const BACKGROUNDS = Object.assign({}, BUILTIN_BACKGROUNDS);

/** 把 BACKGROUNDS 恢复为内置库（云端数据不可用时用） */
function seedBuiltinBackgrounds() {
  Object.keys(BACKGROUNDS).forEach((k) => delete BACKGROUNDS[k]);
  Object.keys(BUILTIN_BACKGROUNDS).forEach((k) => {
    BACKGROUNDS[k] = Object.assign({}, BUILTIN_BACKGROUNDS[k]);
  });
}

/** 通用预设尺寸（一寸/二寸…）。内置版为空，由云端下发填充（阶段 2） */
const PRESETS = [];

const EXAMS = [
  {
    id: 'kaoyan',
    aliases: ['考研', '硕士', '研究生', '研招', '报名照'],
    name: '考研',
    fullName: '硕士研究生招生考试',
    group: '学历考试',
    stage: '网上确认 / 准考证照片',
    priority: 'P0',
    backgrounds: ['white', 'blue'],
    backgroundNote: '多数报考点要求白底，部分接受白底或蓝底',
    image: {
      format: ['jpg', 'jpeg'],
      pxRule: 'ratio', // 只约束比例
      aspectRatio: '3:4',
      recommendPx: [480, 640],
      sizeRule: 'max',
      maxSizeKB: 10240,
      sizeNote: '建议不超过 10M（各报考点表述为『建议大小不超过 10M』）',
    },
    portrait: {
      headShoulderRatio: '头部和肩部占画面 2/3',
      eyeLineTopPercent: '30%~50%',
      angleToleranceDeg: 10,
    },
    notes: [
      '近三个月内正面、免冠、无妆、彩色头像',
      '不得佩戴眼镜、隐形眼镜、美瞳',
      '不得 PS 修改人像特征，不得翻拍',
      '手持身份证照需另行上传（本产品暂不支持）',
    ],
  },
  {
    id: 'ntce-written',
    aliases: ['教资', '教师资格证', '笔试', 'ntce'],
    name: '教师资格 · 笔试报名',
    fullName: '中小学教师资格考试（笔试报名）',
    group: '职业资格',
    stage: '笔试报名 · 上传照片',
    priority: 'P0',
    backgrounds: ['white'],
    backgroundNote: '白色背景为佳',
    image: {
      format: ['jpg', 'jpeg'],
      pxRule: 'range',
      maxPx: [400, 600],
      aspectRatio: '3:4',
      recommendPx: [300, 400],
      sizeRule: 'max',
      maxSizeKB: 200,
      sizeNote: '不大于 200K，压缩品质系数不低于 60',
    },
    portrait: {
      headShoulderRatio: '显示头部和肩的上部',
      eyeLineTopPercent: '眼睛距上边沿 200~300 像素（按 150×200 基准）',
      faceWidthPx: '180~300 像素',
      hairlineTopPx: '50~110 像素',
    },
    notes: [
      '近 6 个月以内免冠正面彩色证件照',
      '不得戴帽子、头巾、发带、墨镜',
      '头发不得遮挡眉毛、眼睛、耳朵',
      '不得美颜、特效、翻拍',
      '部分省份公告给出 150×200 像素、比例约 3:4',
    ],
  },
  {
    id: 'ntce-certify',
    aliases: ['教资', '教师资格证', '认定', '普通话'],
    name: '教师资格 · 认定',
    fullName: '教师资格认定申报',
    group: '职业资格',
    stage: '认定申报 · 上传照片',
    priority: 'P1',
    backgrounds: ['white'],
    backgroundNote: '必须白底，否则不予认定通过',
    image: {
      format: ['jpg'],
      pxRule: 'range',
      minPx: [291, 409],
      maxPx: [299, 417],
      recommendPx: [295, 413],
      sizeRule: 'max',
      maxSizeKB: 190,
      sizeNote: '小于 190K（jpeg/jpg）',
    },
    portrait: {
      headShoulderRatio: '免冠正面，头部居中',
    },
    notes: [
      '像素区间极窄：宽 291~299、高 409~417，务必使用推荐尺寸 295×413',
      '体检表粘贴用的一寸照需与电子照同版',
    ],
  },
  {
    id: 'guokao',
    aliases: ['公务员', '国考', '公考'],
    name: '国家公务员（国考）',
    fullName: '中央机关及其直属机构考试录用公务员',
    group: '公职考试',
    stage: '报名确认 · 上传照片',
    priority: 'P0',
    backgrounds: ['white', 'blue'],
    backgroundNote: '蓝底或白底均可',
    image: {
      format: ['jpg', 'jpeg'],
      pxRule: 'min',
      minPx: [295, 413],
      recommendPx: [295, 413],
      sizeRule: 'range',
      minSizeKB: 20,
      maxSizeKB: 100,
      sizeNote: '20~100KB；官方要求经『照片处理工具』处理后上传',
    },
    portrait: {
      headShoulderRatio: '免冠正面证件照',
    },
    notes: [
      '必须使用官方『照片处理工具』处理并保存后才能上传（本产品输出可直接尝试上传，若审核系统强校验请以官方工具为准）',
      '照片不得美化、修饰',
      '禁止把小像素照片放大后使用',
    ],
  },
  {
    id: 'cet',
    aliases: ['四六级', 'cet4', 'cet6', '英语四级', '英语六级'],
    name: '大学英语四六级',
    fullName: '全国大学英语四、六级考试（CET）',
    group: '学业考试',
    stage: '报名 · 上传照片',
    priority: 'P0',
    backgrounds: ['white', 'lightBlue'],
    backgroundNote: '纯白或浅蓝二选一；部分院校指定其中一种，无特殊要求优先白底',
    image: {
      format: ['jpg', 'jpeg'],
      pxRule: 'exact',
      recommendPx: [295, 413],
      altPx: [
        [144, 192],
        [480, 640],
      ],
      aspectRatio: '2:3（295×413）',
      sizeRule: 'range',
      minSizeKB: 20,
      maxSizeKB: 200,
      sizeNote: '20KB~200KB；部分院校更严（≤100KB / ≤60KB），以本校通知为准',
      dpi: 300,
    },
    portrait: {
      headShoulderRatio: '头部（含颈部）占 7/10、肩部占 1/5',
      topMarginRatio: '头上部空 1/10，左右各空 1/10',
    },
    notes: [
      '后缀须为小写 .jpg',
      '拍照不得化妆、美瞳、过度美颜，不得遮挡眉眼耳',
      '部分院校要求文件以身份证号或学号命名，需按本校通知',
    ],
  },
  {
    id: 'driving-license',
    name: '驾驶证 / 电子驾照',
    fullName: '机动车驾驶证（含交管12123电子驾照）',
    group: '交通证件',
    stage: '申领 / 换证 · 交管12123',
    priority: 'P0',
    backgrounds: ['white'],
    backgroundNote: '必须白底（GA482-2012 行标）',
    image: {
      format: ['jpg', 'jpeg'],
      pxRule: 'exact',
      recommendPx: [520, 756],
      aspectRatio: '22:32（小一寸）',
      sizeRule: 'max',
      maxSizeKB: 300,
      sizeNote: '不超过 300KB（交管12123 电子驾照要求）',
    },
    portrait: {
      headShoulderRatio: '头部（发顶到下巴）约占照片高度 2/3，头宽约占照片宽度 2/3',
      cropFaceRatio: 0.5,
    },
    notes: [
      '申请前 6 个月内的直边正面免冠彩色半身照（GA482-2012）',
      '数字相片有头部尺寸校验：头宽 331~378px、头长（发顶到下巴）449~520px，制作后请以 12123 预览为准微调',
      '不得对人像特征做技术处理、不得合成，过度美颜会被驳回',
      '部分地区办理需『数字照片采集回执』（照相馆/检测平台出具），本产品输出不等于回执',
      '交管12123 自带拍照功能，若本产品产出被驳回，可用 12123 内拍摄重试',
    ],
  },
  {
    id: 'generic-red-1inch',
    aliases: ['红底', '一寸', '党员', '入党', '团员', '档案', '学籍', '保险', '医保', '工作证', '简历'],
    name: '通用一寸 · 红底',
    fullName: '通用红底一寸证件照（党团材料 / 学籍档案 / 保险医保等）',
    group: '档案材料 / 通用',
    stage: '组织关系 / 档案 / 纸质材料',
    priority: 'P1',
    backgrounds: ['red'],
    backgroundNote: '深红 / 枣红底（RGB 184,2,2 照相馆标准色），忌穿红、橙、粉色衣服',
    image: {
      format: ['jpg', 'jpeg'],
      pxRule: 'exact',
      recommendPx: [295, 413],
      aspectRatio: '25:35（一寸，300dpi）',
      sizeRule: 'max',
      maxSizeKB: 500,
      sizeNote: '纸质材料多用，无官方体积限制；≤500KB 便于传输与打印',
    },
    portrait: {
      headShoulderRatio: '常规证件照构图：头部居中，肩部自然入画',
    },
    notes: [
      '适用：入党 / 团员组织关系材料、中小学学籍档案、保险医保纸质备案、工作证、部分证书纸质存档',
      '红底为传统档案用色，具体以接收单位要求为准（电子系统多为白/蓝底）',
      '一寸照纸质打印约 25×35mm，打印时按原比例出图、勿拉伸',
      '结婚登记照为红底大二寸双人合影（约 4×6cm），与本模板不同，本产品暂不支持双人照',
    ],
  },
];

// ------------------------------------------------------------------ 辅助函数

function allExams() {
  return EXAMS;
}

function getExam(id) {
  return EXAMS.find((e) => e.id === id) || null;
}

function getBackground(id) {
  return BACKGROUNDS[id] || BACKGROUNDS.white;
}

/** 考试的底色选项数组（供制作页选择） */
function examBackgrounds(exam) {
  return (exam.backgrounds || ['white']).map((id) => getBackground(id));
}

/** 规格摘要一句话，用于列表卡片 */
function sizeSummary(exam) {
  const img = exam.image;
  const px = `${img.recommendPx[0]}×${img.recommendPx[1]}`;
  let size = '';
  if (img.sizeRule === 'max') size = `≤${img.maxSizeKB >= 1024 ? img.maxSizeKB / 1024 + 'M' : img.maxSizeKB + 'K'}`;
  else if (img.sizeRule === 'range') size = `${img.minSizeKB}~${img.maxSizeKB}K`;
  return `${px} · ${size}`;
}

/** 简单关键词搜索：匹配名称/全称/分组/阶段/别名 */
function search(keyword) {
  const k = String(keyword || '').trim().toLowerCase();
  if (!k) return EXAMS;
  return EXAMS.filter((e) =>
    [e.name, e.fullName, e.group, e.stage, (e.aliases || []).join(' ')].some((s) =>
      s.toLowerCase().includes(k)
    )
  );
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

const CACHE_KEY = 'photo_specs_cache_v1';
const CACHE_TTL = 24 * 3600 * 1000; // 24h：超过则即使版本没变也重新拉一次（防缓存被写坏）

/** 已应用的库来源：builtin / cache / cloud（「关于」页展示用） */
const SOURCE = { value: 'builtin', version: META.version, updatedAt: META.updatedAt };

/** 云端单条考试规格的格式校验：字段缺失/类型不对一律判为不合格 */
function validExam(e) {
  if (!e || typeof e !== 'object') return false;
  if (!e.id || !e.name) return false;
  const img = e.image;
  if (!img || !Array.isArray(img.recommendPx) || img.recommendPx.length !== 2) return false;
  if (!img.sizeRule) return false;
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
  const exams = (Array.isArray(doc.exams) ? doc.exams : []).filter(validExam);
  // 防御：云端考试列表为空或全部不合格时，保留内置库（否则首页会变空白）
  if (!exams.length) {
    console.warn('[photo][specs] 云端规格库无有效数据，保留现有库');
    return false;
  }
  EXAMS.length = 0;
  exams.forEach((e) => EXAMS.push(e));

  if (Array.isArray(doc.backgrounds) && doc.backgrounds.length) {
    Object.keys(BACKGROUNDS).forEach((k) => delete BACKGROUNDS[k]);
    doc.backgrounds.forEach((b) => {
      const bg = normalizeBackground(b);
      if (bg) BACKGROUNDS[bg.id] = bg;
    });
    // 防御：云端背景全部非法时回退内置库（否则底色选择区会空掉）
    if (!Object.keys(BACKGROUNDS).length) {
      console.warn('[photo][specs] 云端背景数据全部无效，回退内置背景');
      seedBuiltinBackgrounds();
    }
  }
  if (Array.isArray(doc.presets) && doc.presets.length) {
    PRESETS.length = 0;
    doc.presets.forEach((p) => PRESETS.push(p));
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
      if (v.version && v.version === META.version && !stale) {
        console.log('[photo][specs] 云端版本一致，跳过拉取', v.version);
        return { ok: true, updated: false, version: v.version };
      }
      return api.fetchSpecs().then((doc) => {
        if (applyCloud(doc)) {
          saveCache(doc);
          console.log('[photo][specs] 已更新到云端版本', doc.version, '考试数', (doc.exams || []).length);
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

function allPresets() {
  return PRESETS;
}

module.exports = {
  META,
  BACKGROUNDS,
  PRESETS,
  EXAMS,
  allExams,
  allPresets,
  getExam,
  getBackground,
  examBackgrounds,
  sizeSummary,
  search,
  refresh,
  loadCache,
  sourceInfo,
};
