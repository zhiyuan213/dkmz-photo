const api = require('./api');

// 未加载配置时默认走正常权益校验，网络错误不能放行免费导出。
let reviewMode = false;
let loaded = false;
let pending = null;
function isReviewMode() { return reviewMode; }
function load(force) {
  if (pending) return pending;
  if (loaded && !force) return Promise.resolve({ reviewMode });
  pending = api.call('/api/photo/config', 'GET', {}, 8000).then((res) => {
    if (!res || res.ok !== true || typeof res.reviewMode !== 'boolean') throw new Error('服务配置返回异常');
    reviewMode = res.reviewMode;
    loaded = true;
    return { reviewMode };
  }).catch((error) => {
    reviewMode = false;
    loaded = false;
    throw error;
  }).finally(() => { pending = null; });
  return pending;
}
function withConfig(page) {
  page.data = Object.assign({}, page.data, { configLoading: true });
  const unload = page.onUnload;
  page.onUnload = function (...args) {
    this._configUnloaded = true;
    if (unload) return unload.apply(this, args);
  };
  ['onLoad', 'onShow', 'onPullDownRefresh'].forEach((name) => {
    const handler = page[name];
    if (name === 'onPullDownRefresh' && !handler) return;
    page[name] = function (...args) {
      if (name === 'onLoad') this._configUnloaded = false;
      return load(name !== 'onLoad').catch((error) => {
        console.warn('[photo][config] 配置获取失败，使用正常权益校验', error && (error.msg || error.message));
      }).then(() => {
        if (this._configUnloaded) return;
        this.setData({ reviewMode, configLoading: false });
        if (this._result) this.setData({ src: reviewMode ? this._result.path || this._result.previewPath
          : this._result.previewPath || this._result.path });
        if (handler) return handler.apply(this, args);
      });
    };
  });
  ['onSave', 'onBuy'].forEach((name) => {
    const handler = page[name];
    if (!handler) return;
    page[name] = function (...args) {
      if (this._configActionPending || this.data.saving || this.data.saved || this.data.paying) return;
      this._configActionPending = true;
      return load(true).then(() => {
        this.setData({ reviewMode });
        if (name === 'onBuy' && reviewMode) return;
        return handler.apply(this, args);
      }).catch(() => {
        wx.showToast({ title: '配置获取失败，请稍后重试', icon: 'none' });
      }).finally(() => { this._configActionPending = false; });
    };
  });
  return page;
}
module.exports = { isReviewMode, load, withConfig };
