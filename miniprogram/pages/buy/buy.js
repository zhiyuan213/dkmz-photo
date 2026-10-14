const api = require('../../utils/api.js');
const buy = require('../../utils/buy.js');
const config = require('../../utils/config.js');

/** 商品卡辅助文案：会员显示天数，张数包显示张数与单张均价 */
function benefitText(g) {
  if (g.vipDays === 365) return '1 年不限保存次数，云端制作有频率限制';
  if (g.vipDays > 0) return `${g.vipDays} 天不限保存次数，云端制作有频率限制`;
  if (g.quota > 0) {
    const unit = ((g.priceFen || 0) / 100 / g.quota).toFixed(2);
    return `${g.quota} 张高清无水印导出 · 约 ¥${unit}/张`;
  }
  return '—';
}

Page(config.withConfig({
  data: {
    loading: true,
    goods: [],
    entitlement: null,
    cloudEnabled: true,
    error: '',
    paying: false,
    reviewMode: config.isReviewMode(),
    // 云端制作规则默认折叠：首屏先让用户看到价格与张数
    showRules: false,
  },

  onLoad() {
    // 备案模式：深链进入也只显示占位，不拉商品/权益、不出现任何价格
    if (config.isReviewMode()) {
      this.setData({ loading: false });
      return;
    }
    this.setData({ cloudEnabled: api.isCloudEnabled() });
    if (!api.isCloudEnabled()) {
      this.setData({ loading: false, error: '云端服务未配置，暂时无法购买' });
      return;
    }
    this.load();
  },

  onShow() {
    // 从订单页 / 支付收银台返回后刷新权益
    if (!config.isReviewMode() && api.isCloudEnabled() && !this.data.loading) {
      this.load();
    }
  },

  onPullDownRefresh() {
    if (config.isReviewMode()) {
      wx.stopPullDownRefresh();
      return;
    }
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    this.setData({ loading: true, error: '' });
    return Promise.all([api.fetchGoods(), api.fetchEntitlement()])
      .then(([goodsRes, entRes]) => {
        const list = goodsRes && goodsRes.ok ? goodsRes.goods || [] : [];
        // 年会员下架后，「最划算」改为标在张数最多（单张均价最低）的那一档
        const maxQuota = list.reduce((m, g) => Math.max(m, g.quota || 0), 0);
        this.setData({
          goods: list.map((g) => ({
            goodsId: g.goodsId,
            name: g.name,
            priceText: buy.fmtYuan(g.priceFen),
            benefit: benefitText(g),
            vip: (g.vipDays || 0) > 0,
            best: (g.quota || 0) > 0 && g.quota === maxQuota,
          })),
          entitlement: entRes && entRes.ok ? entRes : null,
          loading: false,
          error: goodsRes && goodsRes.ok && list.length === 0 ? '商品暂未上架' : '',
        });
      })
      .catch((err) => {
        console.warn('[photo][buy] 加载失败', err);
        this.setData({
          loading: false,
          error: (err && (err.msg || err.errMsg)) || '加载失败，请下拉重试',
        });
      });
  },

  onBuy(e) {
    if (this.data.paying) return;
    const goods = this.data.goods[e.currentTarget.dataset.index];
    if (!goods) return;
    this.setData({ paying: true });
    buy
      .payGoods({ goodsId: goods.goodsId, priceFen: 0 })
      .then((r) => {
        if (r.ok) {
          this.load(); // 刷新权益概览
        }
      })
      .then(() => this.setData({ paying: false }));
  },

  onGoOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  },

  onToggleRules() {
    this.setData({ showRules: !this.data.showRules });
  },

  onGoHome() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
}));
