const specs = require('./utils/specs.js');
const api = require('./utils/api.js');
const config = require('./utils/config.js');

App({
  onLaunch() {
    // 端上推理 / VisionKit 的能力自检结果，首页直接读
    const info = wx.getAppBaseInfo ? wx.getAppBaseInfo() : wx.getSystemInfoSync();
    this.globalData = {
      sdkVersion: info.SDKVersion || info.version || '未知',
      platform: info.platform || '未知',
      brand: info.brand || '',
      model: info.model || '',
      cloudEnabled: api.isCloudEnabled(),
      reviewMode: config.isReviewMode(),
    };

    config.load().then((settings) => {
      this.globalData.reviewMode = settings.reviewMode;
    }).catch(() => { this.globalData.reviewMode = false; });

    // 云托管初始化（未配 ENV_ID 时静默跳过，全部功能走内置数据）
    // 真正的 init 由 api.js 的 ensureInit 统一负责（懒初始化），这里提前触发一次即可
    api.ensureInit();

    // 规格库：先用本地缓存（同步，秒开），再静默向云端要更新
    specs.loadCache();
    specs.refresh().then((r) => {
      this.globalData.specVersion = specs.META.version;
      this.globalData.specSource = specs.sourceInfo().sourceText;
      if (r && r.updated) {
        console.log('[photo][app] 规格库已更新到', r.version);
        // 页面可能已经渲染了旧列表，通知它重新读一次
        if (typeof this.specChangedCallback === 'function') {
          this.specChangedCallback(r);
        }
      }
    });
  },

  /** 页面注册「规格库已更新」回调（onLoad 挂，onUnload 清） */
  onSpecChanged(cb) {
    this.specChangedCallback = cb;
  },

  globalData: {},
});
