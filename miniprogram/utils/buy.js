/**
 * 虚拟支付购买流程。
 * 移植自 exam-countdown 已真机跑通 ¥6×2 的 utils/buy.js，适配本项目的
 * api.createPayOrder(goodsId, code) 签名与商品模型（张数包 / 会员两种权益）。
 *
 * 完整链路：
 *   1. 购买页展示商品列表（后端 GET /api/photo/pay/goods）
 *   2. 用户点「立即购买」→ wx.login() 取一次性 code
 *      —— signature 需要后端用 code 换 session_key 做用户态签名
 *   3. POST /api/photo/pay/order 下单，后端组装 signData 并双签名
 *      （AppKey 只在服务端，绝不下发）
 *   4. wx.requestVirtualPayment 拉起微信收银台
 *   5. 前端 success 只算「弱确认」——必须回后端查订单状态；发货由微信
 *      推送驱动（云托管消息推送 → /api/photo/pay/notify），后端异步到账，
 *      所以这里轮询几次，没到账就提示稍后自动到账（不会丢单，可查订单页）
 *
 * 失败语义：用户主动取消（errCode=-2）不算错误，静默返回；其余失败 toast。
 */

const api = require('./api');

/** 统一日志前缀（真机调试里搜 [photo][buy] 过滤购买流程） */
function log(tag, ...args) {
  console.log(`[photo][buy][${tag}]`, ...args);
}

/** 分 -> 元字符串 */
function fmtYuan(fen) {
  return (Number(fen) / 100).toFixed(2);
}

/** wx.login 取一次性 code */
function loginCode() {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (r) => (r && r.code ? resolve(r.code) : reject(new Error('获取登录凭证失败，请重试'))),
      fail: () => reject(new Error('获取登录凭证失败，请重试')),
    });
  });
}

/** 拉起微信虚拟支付收银台 */
function requestPay(order) {
  return new Promise((resolve, reject) => {
    if (typeof wx.requestVirtualPayment !== 'function') {
      reject(new Error('当前微信版本不支持虚拟支付，请升级微信后重试'));
      return;
    }
    wx.requestVirtualPayment({
      signData: order.signData,
      paySig: order.paySig,
      signature: order.signature,
      mode: order.mode,
      success: () => resolve(),
      fail: (err) => reject(err || { errCode: -1, errMsg: '支付失败' }),
    });
  });
}

/** 支付后回后端确认发货结果（轮询 3 次，每次间隔 1.2s） */
function confirmDelivered(orderId, times) {
  const left = typeof times === 'number' ? times : 3;
  return api.queryPayOrder(orderId)
    .then((res) => {
      if (res && res.ok && res.delivered) {
        return true;
      }
      if (left <= 1) {
        return false;
      }
      return new Promise((r) => setTimeout(r, 1200)).then(() => confirmDelivered(orderId, left - 1));
    })
    .catch(() => false);
}

/**
 * 购买指定商品（购买页商品卡上的按钮调用）。
 * resolve 结果：
 *   { ok: true, delivered: true }  支付成功且权益已到账
 *   { ok: true, delivered: false } 支付成功，到账确认中（后端异步发货）
 *   { ok: false, cancelled: true } 用户主动取消（收银台 / 授权）
 *   { ok: false }                  其他失败（已 toast）
 */
function payGoods(goods) {
  if (!goods || !goods.goodsId) {
    return Promise.resolve({ ok: false });
  }
  log('order', `下单 goodsId=${goods.goodsId} 价格=${goods.priceFen}分`);
  const p = loginCode()
    .then((code) => api.createPayOrder(goods.goodsId, code))
    .then((res) => {
      if (!res || !res.ok) {
        log('order', `下单失败 goodsId=${goods.goodsId}`, (res && res.msg) || res);
        throw new Error((res && res.msg) || '下单失败，请稍后重试');
      }
      log('order', `下单成功 orderId=${res.orderId}，拉起收银台`);
      return requestPay(res).then(() => confirmDelivered(res.orderId));
    })
    .then((delivered) => {
      log('pay', `购买结束 delivered=${delivered}`);
      wx.showToast({
        title: delivered ? '支付成功，已到账' : '支付成功，稍后自动到账',
        icon: 'none',
        duration: 2000,
      });
      return { ok: true, delivered };
    })
    .catch((err) => {
      // 收银台 / 静默授权里用户点「取消」，微信返回 errCode = -2：静默处理
      if (err && (err.errCode === -2 || err.errno === -2)) {
        log('exit', '用户在收银台取消支付');
        return { ok: false, cancelled: true };
      }
      const msg = (err && (err.msg || err.errMsg)) || '支付失败，请稍后重试';
      log('fail', '购买失败:', err);
      wx.showToast({ title: msg, icon: 'none', duration: 2200 });
      return { ok: false };
    });
  return p;
}

module.exports = { payGoods, confirmDelivered, fmtYuan };
