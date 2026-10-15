/**
 * 网络层：云托管调用封装 + 离线降级。
 *
 * 三种通道（按优先级自动选择）：
 *   ① 云托管容器调用 wx.cloud.callContainer —— **推荐**，免域名白名单、免鉴权，
 *      且云托管会自动注入 X-WX-OPENID 请求头（后端据此识别用户，前端拿不到也伪造不了）
 *   ② 云托管公网域名 wx.request —— 需要在 mp 后台配「request 合法域名」，仅在①不可用时用
 *   ③ 都不配置 —— 返回 rejected，调用方走本地兜底（如规格库用随包内置库），功能不中断
 *
 * ⚠️ 上线前必须把 ENV_ID 填成本小程序的云托管环境 ID（不是 exam-countdown 那个环境的 ID，
 *    两个小程序各有独立环境）。未填时不会报错，只是规格库不会云端更新、支付不可用。
 */

/** 云托管环境 ID（mp 后台 → 云托管 → 环境设置 里那一串） */
const ENV_ID = 'dkmz-backend-d9gp2f0tq91ad9ff4';
/** 云托管服务名（控制台创建服务时填的名字） */
const SERVICE = 'dkmz-backend';
/** 公网域名兜底（不填则不用这条通道），例如 https://dkmz-backend-xxx.sh.run.tcloudbase.com */
const BASE_URL = '';

/** 本地离线调试开关：true 时不发网络请求，直接用内置/mock 数据 */
const USE_MOCK = false;

const TIMEOUT = 12000;

/**
 * 云托管 CallContainer 的两条硬限制（官方《微信云托管开发常识》，2026-10-15 踩坑后补）：
 *   ① **超时不得超过 15s**：「小程序/公众号中 CallContainer 超时时间不得超过 15 s」
 *      → 传更大的值无效，别再用 25s/35s 这种数。
 *   ② **文本请求体上限 100KiB**：「CallContainer 请求大小限制 100K，请求中不建议包含图片」
 *      → 带图片的接口必须自己算体积预算（见 utils/pipeline.js 的 CLOUD_BODY_BUDGET）。
 *      超限时前端只看到 `cloud.callContainer:fail -600001`，**后端完全收不到请求**，
 *      容器日志里也没有任何记录 —— 排查时先怀疑体积，再怀疑服务。
 */
const CLOUD_SEGMENT_TIMEOUT = 14500;   // 抠图接口：15s 上限内留 0.5s 余量
/** 与 pipeline.js 的 CLOUD_BODY_BUDGET 一致：整个 JSON body 的本地守门阈值 */
const CALL_BODY_LIMIT = 95 * 1024;

/**
 * 云托管初始化（懒初始化，只做一次）。
 * 放在真正发起调用前执行，不依赖 app.js 的加载时序 —— 与 exam-countdown 已跑通的写法一致。
 */
let inited = false;
function ensureInit() {
  if (inited || USE_MOCK) return;
  if (!ENV_ID || !wx.cloud || !wx.cloud.init) return;
  try {
    wx.cloud.init({ env: ENV_ID, traceUser: true });
    inited = true;
  } catch (e) {
    console.warn('[photo][api] wx.cloud.init 失败', e);
  }
}

function cloudReady() {
  return !USE_MOCK && !!ENV_ID && !!wx.cloud && !!wx.cloud.callContainer;
}

function httpReady() {
  return !USE_MOCK && !!BASE_URL;
}

/**
 * 统一调用入口。
 * @param {string} path 以 / 开头的接口路径，如 /api/photo/specs
 * @param {string} method GET / POST
 * @param {object} data 请求体（POST）或 query（GET）
 * @param {number} timeoutMs 可选超时（毫秒），云端精修等慢接口传大值
 */
function call(path, method = 'GET', data = {}, timeoutMs = TIMEOUT) {
  return new Promise((resolve, reject) => {
    if (USE_MOCK) {
      reject({ code: 'MOCK_MODE', msg: '离线调试模式' });
      return;
    }
    if (cloudReady()) {
      ensureInit();
      wx.cloud.callContainer({
        config: { env: ENV_ID },
        path,
        method,
        header: { 'X-WX-SERVICE': SERVICE, 'content-type': 'application/json' },
        data,
        timeout: timeoutMs,
        success: (res) => {
          const body = res.data;
          if (body && typeof body === 'object') {
            console.log('[photo][api]', path, '->', JSON.stringify(path === '/api/photo/segment'
              ? { ok: body.ok, code: body.code, beautyApplied: body.beautyApplied, hasImage: !!body.image, hasAlpha: !!body.alpha }
              : body).slice(0, 300));
            resolve(body);
          } else {
            reject({ code: 'BAD_RESPONSE', msg: '响应格式异常', raw: body });
          }
        },
        fail: (err) => {
          console.warn('[photo][api] callContainer 失败', path, err);
          const raw = (err && err.errMsg) || '云托管调用失败';
          // -600001 是链路层拒绝，最常见的两个原因：
          //   ① 请求体超 100KiB 文本上限（带图片的接口最容易踩）
          //   ② 服务正在重启 / 冷启动（刚部署完最典型）
          // 后端日志里查不到任何记录时，基本就是这两条之一。
          const extra = /-600001/.test(raw)
            ? '（云托管通道拒绝：请求体超 100KB 或服务正在重启，请稍后重试）'
            : '';
          reject({ code: 'CONTAINER_FAIL', msg: raw + extra, raw: err });
        },
      });
      return;
    }
    if (httpReady()) {
      wx.request({
        url: BASE_URL + path,
        method,
        data,
        timeout: timeoutMs,
        success: (res) => {
          const body = res.data;
          if (body && typeof body === 'object') {
            console.log('[photo][api]', path, '->', JSON.stringify(path === '/api/photo/segment'
              ? { ok: body.ok, code: body.code, beautyApplied: body.beautyApplied, hasImage: !!body.image, hasAlpha: !!body.alpha }
              : body).slice(0, 300));
            resolve(body);
          } else {
            reject({ code: 'BAD_RESPONSE', msg: '响应格式异常', raw: body });
          }
        },
        fail: (err) => {
          console.warn('[photo][api] http 失败', path, err);
          reject({ code: 'HTTP_FAIL', msg: (err && err.errMsg) || '请求失败', raw: err });
        },
      });
      return;
    }
    reject({ code: 'NO_CHANNEL', msg: '未配置云托管环境，使用本地数据' });
  });
}

// ============================================================
// 规格库
// ============================================================

/** 整库下发（前端启动 / 下拉刷新时调用） */
function fetchSpecs() {
  return call('/api/photo/specs', 'GET');
}

/** 只取版本号（先比版本，避免每次全量拉） */
function fetchSpecVersion() {
  return call('/api/photo/specs/version', 'GET');
}

/** 单条规格（增量更新用） */
function fetchSpec(id) {
  return call('/api/photo/specs/' + id, 'GET');
}

// ============================================================
// 支付与权益
// ============================================================

/** 商品列表 */
function fetchGoods() {
  return call('/api/photo/pay/goods', 'GET');
}

/**
 * 下单：返回 signData / paySig / signature，交给 wx.requestVirtualPayment 拉起支付。
 * @param {string} goodsId 商品 ID
 * @param {string} code    wx.login() 的一次性 code（后端换 session_key 做用户态签名，必需）
 */
function createPayOrder(goodsId, code) {
  return call('/api/photo/pay/order', 'POST', { goodsId, code });
}

/** 查本地订单状态（支付后确认到账） */
function queryPayOrder(orderId) {
  return call('/api/photo/pay/order/' + orderId, 'GET');
}

/** 我的订单（订单中心页） */
function fetchMyOrders() {
  return call('/api/photo/pay/my-orders', 'GET');
}

/** 我的权益（剩余张数 / 会员到期） */
function fetchEntitlement() {
  return call('/api/photo/entitlement', 'GET');
}

/**
 * 导出前消耗一次。
 * @param {string} clientNo 幂等号（一次导出动作一个，重试传同一个）
 */
function consumeExport(clientNo, specId, specOk) {
  return call('/api/photo/entitlement/consume', 'POST', { clientNo, specId, specOk });
}

// ============================================================
// 云端精修（腾讯云 BDA 人像分割，走 dkmz-backend 中转）
// ============================================================

/**
 * 云端精修：上传裁剪图 base64。
 *
 * 两种出口，由 extra.alphaOnly 决定：
 *  - extra.alphaOnly=true（只替换背景，不美颜）：后端只回 alpha 灰度 PNG
 *    （{ok, alpha, width, height}，约 10~30KB），端上用本地裁剪图 + alpha
 *    自己换底。不传 bgHex —— 因为不需要后端换底，也不需要它回 RGB。
 *  - 否则传 bgHex 走「成品图模式」：后端连换底（+可选美肤）一次做完，
 *    返回不透明 JPEG（finished=true），端上直接拿它加水印/导出。
 *    （只传 image 不带 bgHex 时才回透明底 PNG，老版本后端兼容路径。）
 *
 * @param {string} imageBase64 裁剪图 base64（与输出同尺寸）
 * @param {boolean} beautify 是否同时做保守美肤（肤色提亮+磨皮，作用区域=面部皮肤 mask）；
 *                           需后端 BDAB_BEAUTY_ENABLED=true，否则后端忽略并只抠图
 * @param {object} extra {alphaOnly, bgHex, outTop}
 *        alphaOnly：只替换背景（不美颜）时的 alpha-only 出口
 *        bgHex：目标底色 / outTop：裁剪框越出原图的顶部行数
 * 内容安全检测不通过时后端回 { ok:false, code:'RISKY_CONTENT' }（图片违规，需提示用户换图）；
 * 检测通道异常回 code:'SEC_CHECK_UNAVAILABLE'。两种都**不做本地降级**——出片只有云端一条通道。
 */
function cloudSegment(imageBase64, beautify = false, extra = null) {
  const alphaOnly = !!(extra && extra.alphaOnly);
  // alphaOnly 时不带 bgHex：带了后端就会走成品图模式（回 JPEG，失去 alpha 的流量收益）
  const body = { image: imageBase64, beauty: alphaOnly ? false : !!beautify };
  // 内容安全检测的送检图：**完整原图**（未裁剪）。缺了后端只能退检裁剪工作图，
  // 而证件照构图会裁掉头部以下 ⇒ 落在裁剪框外的违规内容会漏检（实测裸照过检）。
  if (extra && extra.checkImage) body.checkImage = extra.checkImage;
  if (alphaOnly) {
    body.alphaOnly = true;
  } else if (extra && extra.bgHex) {
    body.bgHex = extra.bgHex;
    body.outTop = extra.outTop || 0;
  }
  // 抠图（美肤时两次 BDA 调用 + 换底合成）比普通接口慢，但 CallContainer 超时
  // **不得超过 15s**（官方硬限制，传更大的值无效）—— 所以这里取 14.5s，
  // 后端的内容安全检测等待也随之收紧（SEC_CHECK_WAIT_MS 默认 6000）。
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => res && res.code ? resolve(res.code) : reject({ msg: '获取登录凭证失败，请重试' }),
      fail: () => reject({ msg: '登录失败，请稍后重试' }),
    });
  }).then((code) => {
    body.loginCode = code;
    // 本地守门：CallContainer 的文本体上限 100KiB，超限只会回一个含义模糊的
    // `-600001`（后端收不到请求、日志无迹）。这里先算准数并给出可读错误。
    const bytes = JSON.stringify(body).length;   // base64 全为 ASCII，字符数 ≈ 字节数
    if (bytes > CALL_BODY_LIMIT) {
      return Promise.reject({
        code: 'PAYLOAD_TOO_LARGE',
        msg: '照片数据过大（' + Math.round(bytes / 1024) + 'KB / 上限 '
          + Math.round(CALL_BODY_LIMIT / 1024) + 'KB），请换一张照片重试',
      });
    }
    return call('/api/photo/segment', 'POST', body, CLOUD_SEGMENT_TIMEOUT);
  });
}

// ============================================================
// 统一能力探测：给页面判断「要不要显示购买入口」
// ============================================================
function isCloudEnabled() {
  return cloudReady() || httpReady();
}

module.exports = {
  call,
  ensureInit,
  fetchSpecs,
  fetchSpecVersion,
  fetchSpec,
  fetchGoods,
  createPayOrder,
  queryPayOrder,
  fetchMyOrders,
  fetchEntitlement,
  consumeExport,
  cloudSegment,
  isCloudEnabled,
  ENV_ID,
  SERVICE,
  BASE_URL,
  USE_MOCK,
};
