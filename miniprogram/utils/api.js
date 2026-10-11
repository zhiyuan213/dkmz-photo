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
const ENV_ID = '';
/** 云托管服务名（控制台创建服务时填的名字） */
const SERVICE = 'dkmz-backend';
/** 公网域名兜底（不填则不用这条通道），例如 https://dkmz-backend-xxx.sh.run.tcloudbase.com */
const BASE_URL = '';

/** 本地离线调试开关：true 时不发网络请求，直接用内置/mock 数据 */
const USE_MOCK = false;

const TIMEOUT = 12000;

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
 */
function call(path, method = 'GET', data = {}) {
  return new Promise((resolve, reject) => {
    if (USE_MOCK) {
      reject({ code: 'MOCK_MODE', msg: '离线调试模式' });
      return;
    }
    if (cloudReady()) {
      wx.cloud.callContainer({
        config: { env: ENV_ID },
        path,
        method,
        header: { 'X-WX-SERVICE': SERVICE, 'content-type': 'application/json' },
        data,
        timeout: TIMEOUT,
        success: (res) => {
          const body = res.data;
          if (body && typeof body === 'object') {
            console.log('[photo][api]', path, '->', JSON.stringify(body).slice(0, 300));
            resolve(body);
          } else {
            reject({ code: 'BAD_RESPONSE', msg: '响应格式异常', raw: body });
          }
        },
        fail: (err) => {
          console.warn('[photo][api] callContainer 失败', path, err);
          reject({ code: 'CONTAINER_FAIL', msg: (err && err.errMsg) || '云托管调用失败', raw: err });
        },
      });
      return;
    }
    if (httpReady()) {
      wx.request({
        url: BASE_URL + path,
        method,
        data,
        timeout: TIMEOUT,
        success: (res) => {
          const body = res.data;
          if (body && typeof body === 'object') {
            console.log('[photo][api]', path, '->', JSON.stringify(body).slice(0, 300));
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
// 统一能力探测：给页面判断「要不要显示购买入口」
// ============================================================
function isCloudEnabled() {
  return cloudReady() || httpReady();
}

module.exports = {
  call,
  fetchSpecs,
  fetchSpecVersion,
  fetchSpec,
  fetchGoods,
  createPayOrder,
  queryPayOrder,
  fetchMyOrders,
  fetchEntitlement,
  consumeExport,
  isCloudEnabled,
  ENV_ID,
  SERVICE,
  BASE_URL,
  USE_MOCK,
};
